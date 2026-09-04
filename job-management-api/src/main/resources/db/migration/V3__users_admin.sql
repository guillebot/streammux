-- User administration: Grafana-style Entra role override + last-login index.

ALTER TABLE users
    ADD COLUMN entra_roles_overridden BOOLEAN NOT NULL DEFAULT false;

-- Last-login on the admin Users page is derived from auth_audit LOGIN_SUCCESS.
CREATE INDEX idx_auth_audit_login_success
    ON auth_audit (username, ts)
    WHERE event_type = 'LOGIN_SUCCESS';
