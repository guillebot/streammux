# Config Studio

GitLab-backed backup and review for job definitions. **Kafka compacted topics remain the runtime source of truth.**

## Repository

Dedicated GitLab project: **streammux-configs** (layout: `<env>/jobs/<jobId>.json`).

## Enable

```text
CONFIG_STUDIO_ENABLED=true
CONFIG_STUDIO_GITLAB_PROJECT_ID=<numeric id or group/project>
CONFIG_STUDIO_GITLAB_TOKEN=<gitlab PAT with api scope>
CONFIG_STUDIO_DEFAULT_ENVIRONMENT=onelab
```

Requires `STREAMMUX_AUTH_ENABLED=true` and Postgres (Flyway migration `V2__config_studio.sql`).

## API

| Method | Path | Description |
| ------ | ---- | ----------- |
| GET | `/api/config-studio/status` | Drift / last sync overview |
| POST | `/api/config-studio/validate` | Validate Git tree JSON |
| POST | `/api/config-studio/sync?dryRun=` | Git → Kafka reconcile |
| POST | `/api/config-studio/submit` | Kafka → GitLab MR |

## Operator workflow

1. **Bootstrap:** export all live jobs once (Submit → merge MR) before relying on Git.
2. **Day-to-day edits:** change jobs in the UI (writes Kafka immediately); optionally Submit to open a backup MR.
3. **Disaster recovery / drift fix:** Validate → Sync dry-run → Sync live (admin).

## UI

Sidebar → **Config Studio** — overview, validate, dry-run sync, live sync, export MR.

## Semantics

- **Submit:** serializes in-memory jobs to `env/jobs/*.json` on a branch + MR.
- **Sync:** reads Git at ref, validates each file, upserts/deletes jobs in Kafka to match Git.
- **Drift:** compare Git HEAD SHA vs `config_studio_sync_state.last_git_sha` per environment.
