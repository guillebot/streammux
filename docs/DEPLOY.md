# Streammux CD: deploy guide

This document covers **how releases are tagged**, **how images reach hosts**, and
Ansible deploy promotion. Production (kstreams1–4) and OneLab (techarch-kapps)
are deployed via **Ansible** in the `devops` repo with a pinned
`streammux_image_tag` in inventory.

For build / test / image-push, see [`.gitlab-ci.yml`](../.gitlab-ci.yml).

---

## Release tags (`release:tag`) — GitLab CI, not Ansible

**`release:tag` is an automatic GitLab CI job on `main`**, not an Ansible
playbook. Ansible only deploys whatever tag you pin in inventory
(`streammux_image_tag`).

### What it does

1. Computes the next immutable tag `YYYYMMDD-NN` (UTC date + 2-digit daily
   counter) from existing git tags — see
   [`scripts/next-release-tag.sh`](../scripts/next-release-tag.sh).
2. Creates an **annotated git tag** on the current `main` commit and pushes it
   to GitLab.
3. Triggers **`release:images`**, which builds and pushes all five container
   images tagged **only** with that release ID (no `:latest`).

Images: `job-management-api`, `site-orchestrator`, `web-ui`, `job-catalog-api`,
`mcp`.

### How it runs

1. Merge your changes to **`main`**.
2. The **`main`** pipeline runs tests, then **`release:tag`** automatically
   (no manual Play step).
3. When `release:tag` succeeds, **`release:images`** runs and pushes
   `:YYYYMMDD-NN` to the Container Registry.

To inspect a release: GitLab → **Streammux project** → **Build** →
**Pipelines** → open the latest **`main`** pipeline and check the **release**
stage.

Dry-run locally (no push):

```bash
scripts/next-release-tag.sh              # prints e.g. 20260825-01
scripts/release-tag.sh --dry-run         # shows tag + commit, no git changes
```

### Required setup (automatic `release:tag`)

Automatic tagging on every `main` merge **requires** git push permission from CI.
Without it, `release:tag` fails with *403 forbidden*, `release:images` is skipped,
and no `YYYYMMDD-NN` images are published.

Configure **one** of:

1. **GitLab project** → **Settings** → **CI/CD** → **Job token permissions** →
   enable **Allow Git push requests to the repository** (for `CI_JOB_TOKEN`), or
2. Add masked CI variable **`RELEASE_GIT_PUSH_TOKEN`** — project access token
   with `write_repository` scope.

Verify after merge: main pipeline **release** stage shows `release:tag` and
`release:images` **success**; git tag and registry image exist for the same
`YYYYMMDD-NN`.

### CI variable for tag push

Tag push uses `CI_JOB_TOKEN` by default. If push fails with *403 forbidden*,
add a **Project access token** (or deploy token with `write_repository`) as
masked CI variable **`RELEASE_GIT_PUSH_TOKEN`**. Enable *CI job token* →
*Allow Git push* under **Settings → CI/CD → Job token permissions** if you
prefer token-only.

### Promote OneLab → production (Ansible)

After `release:images` finishes:

| Step | Action |
|------|--------|
| 1 | MR in **`devops`**: set `streammux_image_tag: "YYYYMMDD-NN"` in OneLab inventory (or role override under `onelab_techarch_kapps`) |
| 2 | `ansible-playbook playbooks/onelab/streammux/deploy.yml --limit onelab_techarch_kapps` |
| 3 | Verify OneLab; bump `streammux_image_tag` in `inventory/group_vars/kafka_streams/streammux.yml` |
| 4 | `ansible-playbook playbooks/kstreams/streammux/deploy.yml --limit kafka_streams` |

**Same git tag** is promoted through environments — images are not rebuilt per env.

On kstreams hosts where registry pull is blocked, pre-load or mirror images for
the release tag before deploy (`streammux_compose_pull: false` in prod inventory).

### Rollback (Ansible)

Redeploy a previous **`streammux_image_tag`** value via devops MR + the same
playbook. Release tags and registry images are immutable.

Re-run **`release:images:retag`** from an older git tag pipeline if registry
images were deleted but the git tag still exists.

---

## Pipeline shape (GitLab CI)

```
push to main  ->  test  ->  package (SHA builds on every pipeline)
                    release (automatic release:tag after tests)
                         ->  release:images (YYYYMMDD-NN only)
tag YYYYMMDD-NN  ->  release:images:retag (rebuild registry from tag)
```

| Job | Stage | Trigger | Notes |
|-----|-------|---------|-------|
| `release:tag` | `release` | Automatic on `main` after tests | Git tag + dotenv |
| `release:images` | `release` | After `release:tag` succeeds | Five images, one tag |
| `release:images:retag` | `release` | Tag pipeline `^\d{8}-\d{2}$` | Rebuild without new tag |
| `images:build` | `package` | Branch/MR pipelines | SHA/branch/latest; skips release tags |

Dev/branch builds still publish `:short-sha`, `:branch-slug`, and `:latest` on
`main`. **Prefer Ansible + `streammux_image_tag`** for stage/prod — not
`:latest` or mutable `:main`.

---

## Manual local builds

For emergencies or air-gapped builds, use
[`build_and_push.sh`](../build_and_push.sh) with an explicit release tag:

```bash
export IMAGE_REPO=registry.gitlab.com/dmr4013905/techarchitecture/techarchitecture/streammux
docker login registry.gitlab.com
./build_and_push.sh -v 20260825-01
```

Primary release path remains GitLab **`release:tag`** + **`release:images`**.
