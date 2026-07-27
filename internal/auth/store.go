package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

const TokenPrefix = "stm_"

var (
	ErrUnauthorized = errors.New("unauthorized")
	ErrForbidden    = errors.New("forbidden")
)

// Principal is the authenticated MCP caller (implicit admin user).
type Principal struct {
	ID            int64
	Name          string
	DisplayPrefix string
	Scopes        map[string]struct{}
	Role          string
}

// Store manages PAT tokens in SQLite.
type Store struct {
	db *sql.DB
}

// TokenRecord is metadata returned by list (never the secret).
type TokenRecord struct {
	ID            int64      `json:"id"`
	Name          string     `json:"name"`
	DisplayPrefix string     `json:"display_prefix"`
	Scopes        []string   `json:"scopes"`
	Role          string     `json:"role"`
	CreatedAt     time.Time  `json:"created_at"`
	LastUsedAt    *time.Time `json:"last_used_at,omitempty"`
	RevokedAt     *time.Time `json:"revoked_at,omitempty"`
}

func Open(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	s := &Store{db: db}
	if err := s.migrate(); err != nil {
		_ = db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error {
	if s.db == nil {
		return nil
	}
	return s.db.Close()
}

func (s *Store) migrate() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS tokens (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  display_prefix TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  scopes TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'ADMIN',
  created_at TEXT NOT NULL,
  last_used_at TEXT,
  revoked_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_tokens_hash ON tokens(token_hash);
`)
	return err
}

func (s *Store) Validate(bearer string) (*Principal, error) {
	token := extractToken(bearer)
	if token == "" || !strings.HasPrefix(token, TokenPrefix) {
		return nil, ErrUnauthorized
	}
	hash := hashToken(token)
	row := s.db.QueryRow(`
SELECT id, name, display_prefix, scopes, role, revoked_at
FROM tokens WHERE token_hash = ?`, hash)
	var id int64
	var name, prefix, scopesCSV, role string
	var revoked sql.NullString
	if err := row.Scan(&id, &name, &prefix, &scopesCSV, &role, &revoked); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrUnauthorized
		}
		return nil, err
	}
	if revoked.Valid && revoked.String != "" {
		return nil, ErrUnauthorized
	}
	scopes := parseScopes(scopesCSV)
	if _, ok := scopes["mcp"]; !ok {
		return nil, ErrForbidden
	}
	_, _ = s.db.Exec(`UPDATE tokens SET last_used_at = ? WHERE id = ?`, time.Now().UTC().Format(time.RFC3339), id)
	return &Principal{
		ID:            id,
		Name:          name,
		DisplayPrefix: prefix,
		Scopes:        scopes,
		Role:          role,
	}, nil
}

func (s *Store) Create(name string, scopes []string, role string) (plaintext string, rec TokenRecord, err error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return "", TokenRecord{}, errors.New("name is required")
	}
	if role == "" {
		role = "ADMIN"
	}
	scopeSet := map[string]struct{}{}
	for _, sc := range scopes {
		sc = strings.TrimSpace(strings.ToLower(sc))
		if sc != "" {
			scopeSet[sc] = struct{}{}
		}
	}
	scopeSet["mcp"] = struct{}{}
	plain, prefix, hash, err := generateToken()
	if err != nil {
		return "", TokenRecord{}, err
	}
	now := time.Now().UTC()
	res, err := s.db.Exec(`
INSERT INTO tokens (name, display_prefix, token_hash, scopes, role, created_at)
VALUES (?, ?, ?, ?, ?, ?)`,
		name, prefix, hash, joinScopes(scopeSet), role, now.Format(time.RFC3339))
	if err != nil {
		return "", TokenRecord{}, err
	}
	id, _ := res.LastInsertId()
	return plain, TokenRecord{
		ID:            id,
		Name:          name,
		DisplayPrefix: prefix,
		Scopes:        scopesList(scopeSet),
		Role:          role,
		CreatedAt:     now,
	}, nil
}

func (s *Store) List() ([]TokenRecord, error) {
	rows, err := s.db.Query(`
SELECT id, name, display_prefix, scopes, role, created_at, last_used_at, revoked_at
FROM tokens ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []TokenRecord
	for rows.Next() {
		var r TokenRecord
		var scopesCSV, created, lastUsed, revoked sql.NullString
		if err := rows.Scan(&r.ID, &r.Name, &r.DisplayPrefix, &scopesCSV, &r.Role, &created, &lastUsed, &revoked); err != nil {
			return nil, err
		}
		r.Scopes = scopesList(parseScopes(scopesCSV.String))
		if created.Valid {
			r.CreatedAt, _ = time.Parse(time.RFC3339, created.String)
		}
		if lastUsed.Valid && lastUsed.String != "" {
			t, _ := time.Parse(time.RFC3339, lastUsed.String)
			r.LastUsedAt = &t
		}
		if revoked.Valid && revoked.String != "" {
			t, _ := time.Parse(time.RFC3339, revoked.String)
			r.RevokedAt = &t
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

func (s *Store) Revoke(id int64) error {
	res, err := s.db.Exec(`UPDATE tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`,
		time.Now().UTC().Format(time.RFC3339), id)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return fmt.Errorf("token not found or already revoked")
	}
	return nil
}

func (p *Principal) HasScope(scope string) bool {
	_, ok := p.Scopes[strings.ToLower(scope)]
	return ok
}

func (p *Principal) RequireScope(scope string) error {
	if !p.HasScope(scope) {
		return fmt.Errorf("%w: missing scope %q", ErrForbidden, scope)
	}
	return nil
}

func extractToken(authz string) string {
	authz = strings.TrimSpace(authz)
	if len(authz) < 8 {
		return ""
	}
	if strings.HasPrefix(strings.ToLower(authz), "bearer ") {
		return strings.TrimSpace(authz[7:])
	}
	return ""
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func generateToken() (plaintext, displayPrefix, hash string, err error) {
	const alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
	b := make([]byte, 32)
	if _, err = rand.Read(b); err != nil {
		return
	}
	var body strings.Builder
	for _, by := range b {
		body.WriteByte(alphabet[int(by)%len(alphabet)])
	}
	plaintext = TokenPrefix + body.String()
	displayPrefix = plaintext[:12] + "…"
	hash = hashToken(plaintext)
	return
}

func parseScopes(csv string) map[string]struct{} {
	m := map[string]struct{}{}
	for _, p := range strings.Split(csv, ",") {
		p = strings.TrimSpace(strings.ToLower(p))
		if p != "" {
			m[p] = struct{}{}
		}
	}
	return m
}

func joinScopes(m map[string]struct{}) string {
	return strings.Join(scopesList(m), ",")
}

func scopesList(m map[string]struct{}) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}
