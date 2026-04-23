# Publishing Streammux docs to Confluence

This file explains how to use **`STREAMMUX.confluence.md`** with Confluence **without** committing API tokens or passwords to the repository.

## Option A: Manual (fastest)

1. Open [STREAMMUX.confluence.md](STREAMMUX.confluence.md).
2. In Confluence, create or edit a page in the target space.
3. Paste the Markdown. If your site uses a Markdown macro or native Markdown editor, use that; otherwise paste into a Markdown macro body so headings and tables render cleanly.

## Option B: Confluence REST API

Confluence Cloud’s REST API typically expects page bodies in **storage format** (Confluence wiki XML) or **Atlassian Document Format (ADF)**, not raw Markdown. Common approaches:

1. **Convert Markdown to storage or ADF** using a trusted internal tool or a one-off script, then `POST` or `PUT` via the REST API.
2. Use an **Atlassian API token** (recommended) with your Atlassian account email as the username and the token as the password for HTTP Basic authentication against `https://<your-site>.atlassian.net`.

**Do not** put tokens in the repo, in `STREAMMUX.confluence.md`, or in committed shell scripts. Use environment variables or your CI secret store.

Example pattern (illustrative only—adjust URLs, body format, and IDs for your Cloud or Data Center version):

```bash
# Set these in your shell or CI secrets (never commit values):
# export ATLASSIAN_USER='you@company.com'
# export ATLASSIAN_API_TOKEN='your-api-token'
# export CONFLUENCE_BASE='https://your-site.atlassian.net/wiki'

curl -sS -u "${ATLASSIAN_USER}:${ATLASSIAN_API_TOKEN}" \
  -H 'Content-Type: application/json' \
  "${CONFLUENCE_BASE}/rest/api/content/<PAGE_ID>" \
  -d @payload.json
```

Official reference: [Confluence Cloud REST API](https://developer.atlassian.com/cloud/confluence/rest/v1/intro/) (verify the API version your organization uses).

### Automated publish (this repository)

[`docs/tools/publish_confluence_page.py`](tools/publish_confluence_page.py) converts [STREAMMUX.confluence.md](STREAMMUX.confluence.md) to Confluence **storage (XHTML)** and **creates or updates** a page with the same title (matched via CQL) in the target space.

```bash
cd /path/to/streammux
export ATLASSIAN_EMAIL='you@company.com'   # same email you use for Atlassian / Confluence Cloud
export ATLASSIAN_API_TOKEN='your-api-token' # from https://id.atlassian.com/manage-profile/security/api-tokens
# Optional overrides:
# export CONFLUENCE_HOST=oneoptimum.atlassian.net
# export CONFLUENCE_SPACE='~gschimme'

python3 docs/tools/publish_confluence_page.py docs/STREAMMUX.confluence.md
```

**TA hub landing page:** [`docs/tools/create_ta_hub_landing_page.py`](tools/create_ta_hub_landing_page.py) creates or updates **Technical Architecture (TA) — documentation hub** in your personal space (`~gschimme` by default), grouping TA pages you created into ingest → bus → storage/observability → mirroring. Run with the same `ATLASSIAN_*` variables; use `--dry-run` to print bucket counts only.

```bash
python3 docs/tools/create_ta_hub_landing_page.py --dry-run
python3 docs/tools/create_ta_hub_landing_page.py
```

**Troubleshooting**

- **401** on Jira/Atlassian APIs: email and token do not match, or the token was revoked. Regenerate the token; use the **exact** Atlassian account email (often your work email for a company site), not necessarily `git config user.email`.
- **403** “caller cannot access Confluence”: the account has no **Confluence** access on that Cloud site, or SSO/policy blocks API use. Confirm you can open the space in the browser while logged in as that user.
- **Space not found**: personal space keys sometimes differ from the URL nickname. List spaces with `GET /wiki/rest/api/space?limit=100` (authenticated) and set `CONFLUENCE_SPACE` to the real `key`.

## Option C: Marketplace / internal importers

Some organizations use Confluence apps or internal pipelines that accept Markdown or sync from Git. Prefer whatever your platform team supports; keep **`STREAMMUX.confluence.md`** as the canonical Markdown source in git.

## Checklist before publishing

- [ ] Strip or redact any environment-specific hostnames if the wiki is wider than the lab audience.
- [ ] Confirm topic allowlist examples match your written policy.
- [ ] Link from the Confluence page to the canonical git repository for engineers.
