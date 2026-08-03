package auth

import (
	"encoding/json"
	"errors"
	"path/filepath"
	"strings"
	"testing"
)

func TestStoreCreateValidateRevoke(t *testing.T) {
	store := openTestStore(t)

	plain, rec, err := store.Create("ci-reader", []string{"mcp", "docs", "read"}, "READER")
	if err != nil {
		t.Fatalf("Create() error = %v", err)
	}
	if rec.ID == 0 {
		t.Fatal("expected non-zero token id")
	}
	if plain == "" || !strings.HasPrefix(plain, TokenPrefix) {
		t.Fatalf("unexpected plaintext token: %q", plain)
	}

	principal, err := store.Validate("Bearer " + plain)
	if err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
	if principal.Name != "ci-reader" {
		t.Fatalf("name = %q", principal.Name)
	}
	if err := principal.RequireScope("read"); err != nil {
		t.Fatalf("RequireScope(read) = %v", err)
	}
	if err := principal.RequireScope("write"); err == nil {
		t.Fatal("expected missing write scope to fail")
	} else if !errors.Is(err, ErrForbidden) {
		t.Fatalf("expected ErrForbidden, got %v", err)
	}

	if _, err := store.Validate("Bearer " + plain + "x"); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("bad token: got %v, want ErrUnauthorized", err)
	}
	if _, err := store.Validate(""); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("empty auth: got %v, want ErrUnauthorized", err)
	}
	if _, err := store.Validate(plain); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("missing Bearer prefix: got %v, want ErrUnauthorized", err)
	}

	if err := store.Revoke(rec.ID); err != nil {
		t.Fatalf("Revoke() error = %v", err)
	}
	if _, err := store.Validate("Bearer " + plain); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("revoked token: got %v, want ErrUnauthorized", err)
	}
}

func TestCreateRequiresMcpScopeEvenWhenOmitted(t *testing.T) {
	store := openTestStore(t)

	plain, _, err := store.Create("docs-only-request", []string{"docs"}, "ADMIN")
	if err != nil {
		t.Fatalf("Create() error = %v", err)
	}
	principal, err := store.Validate("Bearer " + plain)
	if err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
	if !principal.HasScope("mcp") {
		t.Fatal("Create should always attach mcp scope")
	}
	if !principal.HasScope("docs") {
		t.Fatal("expected docs scope")
	}
}

func TestListDoesNotExposeSecrets(t *testing.T) {
	store := openTestStore(t)
	plain, _, err := store.Create("list-me", []string{"mcp", "admin"}, "ADMIN")
	if err != nil {
		t.Fatalf("Create() error = %v", err)
	}
	recs, err := store.List()
	if err != nil {
		t.Fatalf("List() error = %v", err)
	}
	if len(recs) != 1 {
		t.Fatalf("len(List()) = %d", len(recs))
	}
	raw, err := json.Marshal(recs[0])
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), plain) {
		t.Fatal("List() leaked plaintext token")
	}
}

func openTestStore(t *testing.T) *Store {
	t.Helper()
	path := filepath.Join(t.TempDir(), "tokens.db")
	store, err := Open(path)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	t.Cleanup(func() { _ = store.Close() })
	return store
}
