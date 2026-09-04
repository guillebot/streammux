-- Config Studio sync state

CREATE TABLE config_studio_sync_state (
    environment  TEXT PRIMARY KEY,
    last_git_sha TEXT NOT NULL,
    synced_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
