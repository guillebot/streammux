#!/usr/bin/env python3
"""
Create or update a curated hub page in your personal Confluence space that indexes
TA (Technical Architecture) pages you created, grouped as:

  ingest → bus → storage/observability → mirroring

Authentication (never commit tokens):
  export ATLASSIAN_EMAIL='you@company.com'
  export ATLASSIAN_API_TOKEN='your-api-token'

Env:
  CONFLUENCE_HOST     default oneoptimum.atlassian.net
  HUB_SPACE_KEY       default ~gschimme (where the landing page is created)
  SOURCE_SPACE_KEY    default TA (pages to index — filtered by creator = current user)

Usage:
  python3 docs/tools/create_ta_hub_landing_page.py
  python3 docs/tools/create_ta_hub_landing_page.py --dry-run
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Dict, List, Literal, Optional, Tuple

Category = Literal["ingest", "bus", "storage", "mirroring", "misc"]

MANUAL_CATEGORY: Dict[str, Category] = {
    "Available Kafka topics - Data samples": "bus",
    "Observability Sample Messages": "bus",
    "Insights data pipeline requests": "bus",
    "Project Streamlens": "bus",
    "Configure proxy on debian": "misc",
    "OneLAB Freeradius + TACACS+": "misc",
    "How-to articles": "misc",
}


def categorize(title: str) -> Category:
    if title in MANUAL_CATEGORY:
        return MANUAL_CATEGORY[title]
    low = title.lower()

    if "data mirroring between kafka" in low:
        return "mirroring"
    if "mirroring - geo" in low or "geo replica" in low:
        return "mirroring"
    if "csg kafka to bluenet" in low:
        return "mirroring"
    if "rednet kafka to onelab" in low:
        return "mirroring"
    if "rednet kafka to nrby" in low:
        return "mirroring"

    # Downstream of Kafka that also mentions Splunk/Cribble in the title
    if "splunk" in low or "cribble" in low:
        return "storage"

    # Primary ingest into Kafka (evaluate before generic "Grafana" storage match)
    if "connecting to kafka" in low:
        return "bus"
    if "non-kafka ingestion" in low:
        return "ingest"
    if " module" in low or low.endswith("module"):
        return "ingest"
    if "telemetry streaming" in low and "kafka" in low:
        return "ingest"
    if "http, https, dns" in low:
        return "ingest"
    if "to kafka bus" in low or " to kafka" in low:
        if "from kafka" in low:
            return "storage"
        return "ingest"
    if "trap" in low and "kafka" in low:
        return "ingest"

    if "from kafka bus to mimir" in low or "from kafka bus to loki" in low:
        return "storage"
    if low in ("logs", "metrics"):
        return "storage"
    if "observability architecture" in low:
        return "storage"
    if "observability platform internal" in low:
        return "storage"
    if "observability quick checklist" in low or "kafka for observability quick checklist" in low:
        return "storage"
    if "kafka clusters and observability" in low:
        return "storage"
    if "grafana" in low:
        return "storage"
    if "system metrics (so" in low:
        return "storage"

    return "bus"


def _basic_auth_header(email: str, token: str) -> str:
    raw = f"{email}:{token}".encode("utf-8")
    return "Basic " + base64.b64encode(raw).decode("ascii")


def _request_json(
    method: str,
    url: str,
    auth: str,
    data: Optional[dict] = None,
) -> Tuple[int, dict]:
    body = None
    headers = {
        "Authorization": auth,
        "Accept": "application/json",
    }
    if data is not None:
        body = json.dumps(data).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(err_body) if err_body else {}
        except json.JSONDecodeError:
            parsed = {"raw": err_body}
        return e.code, parsed


def _paginate_search(base_wiki: str, api: str, auth: str, cql: str) -> List[dict]:
    url = f"{api}/content/search?" + urllib.parse.urlencode(
        {"cql": cql, "limit": 100, "start": 0, "expand": "space"}
    )
    seen: set[str] = set()
    results: List[dict] = []
    while url and url not in seen:
        seen.add(url)
        code, data = _request_json("GET", url, auth)
        if code != 200:
            raise RuntimeError(f"Search failed HTTP {code}: {data}")
        results.extend(data.get("results") or [])
        nxt = (data.get("_links") or {}).get("next")
        if not nxt:
            break
        url = nxt if nxt.startswith("http") else base_wiki + nxt
    return results


def _wiki_link(base_wiki: str, webui: str, title: str) -> str:
    href = base_wiki + webui if webui.startswith("/") else webui
    href = href.replace("&", "&amp;")
    return f'<a href="{href}">{_esc(title)}</a>'


def _esc(s: str) -> str:
    return (
        s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def build_storage_body(base_wiki: str, buckets: Dict[Category, List[Tuple[str, str, str]]]) -> str:
    """buckets: cat -> list of (title, webui, space_key)."""
    intro = (
        "<p>This page is a <strong>curated index</strong> of documentation in the "
        "<strong>Technical Architecture (TA)</strong> space that you created, organized by typical "
        "<em>data-flow</em> stages. Use it to find a canonical entry point before opening leaf pages.</p>"
        "<p><strong>Flow:</strong> ingest (sources into Kafka) → bus (platform, APIs, contracts) → "
        "storage / observability (downstream stacks) → mirroring (cluster-to-cluster).</p>"
        "<hr/>"
    )

    sections: List[Tuple[str, str, Category]] = [
        ("1. Ingest (into the bus)", "Sources, connectors, modules, and edge paths that publish to Kafka.", "ingest"),
        ("2. Bus &amp; platform", "Kafka Bus contracts, APIs, topology, samples, and cross-cutting architecture.", "bus"),
        ("3. Storage &amp; observability", "Grafana, Mimir, Loki, logging/metrics paths off the bus, and ops views.", "storage"),
        ("4. Mirroring &amp; federation", "Kafka-to-Kafka mirroring and cross-environment forwarding.", "mirroring"),
        ("5. Other", "Utilities and pages that do not fit the flow above.", "misc"),
    ]

    parts = [intro]
    for h2, blurb, key in sections:
        items = buckets.get(key, [])
        items = sorted(items, key=lambda x: x[0].lower())
        parts.append(f"<h2>{h2}</h2><p>{blurb}</p>")
        if not items:
            parts.append("<p><em>No pages in this bucket (adjust categorization if needed).</em></p>")
            continue
        lis = "".join(f"<li><p>{_wiki_link(base_wiki, w, t)} <em>({sk})</em></p></li>" for t, w, sk in items)
        parts.append(f"<ul>{lis}</ul>")

    parts.append(
        "<hr/><p><em>Generated from Confluence metadata; regroup by editing "
        "<code>docs/tools/create_ta_hub_landing_page.py</code> in the streammux repo.</em></p>"
    )
    return "".join(parts)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="Print bucket counts and exit")
    args = ap.parse_args()

    host = os.environ.get("CONFLUENCE_HOST", "oneoptimum.atlassian.net").rstrip("/")
    hub_space = os.environ.get("HUB_SPACE_KEY", "~gschimme")
    source_space = os.environ.get("SOURCE_SPACE_KEY", "TA")
    email = os.environ.get("ATLASSIAN_EMAIL", "").strip()
    token = os.environ.get("ATLASSIAN_API_TOKEN", "").strip()
    if not email or not token:
        print("Set ATLASSIAN_EMAIL and ATLASSIAN_API_TOKEN", file=sys.stderr)
        return 2

    base_wiki = f"https://{host}/wiki"
    api = f"{base_wiki}/rest/api"
    auth = _basic_auth_header(email, token)

    code, me = _request_json("GET", f"{api}/user/current", auth)
    if code != 200:
        print(f"user/current failed HTTP {code}: {me}", file=sys.stderr)
        return 1
    aid = me.get("accountId")
    if not aid:
        print("No accountId in user/current", file=sys.stderr)
        return 1

    cql = f'type=page AND space = "{source_space}" AND creator = "{aid}" ORDER BY title ASC'
    try:
        pages = _paginate_search(base_wiki, api, auth, cql)
    except RuntimeError as e:
        print(e, file=sys.stderr)
        return 1

    buckets: Dict[Category, List[Tuple[str, str, str]]] = {
        "ingest": [],
        "bus": [],
        "storage": [],
        "mirroring": [],
        "misc": [],
    }
    for p in pages:
        title = p.get("title") or ""
        webui = (p.get("_links") or {}).get("webui") or ""
        sk = (p.get("space") or {}).get("key") or source_space
        if not webui:
            continue
        cat = categorize(title)
        buckets[cat].append((title, webui, sk))

    if args.dry_run:
        for k in ("ingest", "bus", "storage", "mirroring", "misc"):
            print(f"{k}: {len(buckets[k])} pages")
        return 0

    page_title = "Technical Architecture (TA) — documentation hub"
    storage = build_storage_body(base_wiki, buckets)

    safe_title = page_title.replace("\\", "\\\\").replace('"', '\\"')
    cql_hub = f'type=page AND space="{hub_space}" AND title="{safe_title}"'
    search_url = f"{api}/content/search?" + urllib.parse.urlencode({"cql": cql_hub, "limit": 1})
    code, search = _request_json("GET", search_url, auth)
    if code != 200:
        print(f"Hub search failed HTTP {code}: {search}", file=sys.stderr)
        return 1

    payload_base = {
        "type": "page",
        "title": page_title,
        "space": {"key": hub_space},
        "body": {
            "storage": {
                "value": storage,
                "representation": "storage",
            }
        },
    }

    results = search.get("results") or []
    if not results:
        code, created = _request_json("POST", f"{api}/content", auth, payload_base)
        if code not in (200, 201):
            print(f"Create failed HTTP {code}: {created}", file=sys.stderr)
            return 1
        webui = (created.get("_links") or {}).get("webui", "")
        print(f"Created hub id={created.get('id')} webui={webui}")
        return 0

    page_id = results[0]["id"]
    code, current = _request_json("GET", f"{api}/content/{page_id}?expand=body.storage,version", auth)
    if code != 200:
        print(f"Get hub failed HTTP {code}: {current}", file=sys.stderr)
        return 1
    ver = (current.get("version") or {}).get("number", 1)
    update = {
        **payload_base,
        "id": page_id,
        "version": {"number": ver + 1, "message": "Refresh TA hub index"},
    }
    code, updated = _request_json("PUT", f"{api}/content/{page_id}", auth, update)
    if code != 200:
        print(f"Update failed HTTP {code}: {updated}", file=sys.stderr)
        return 1
    webui = (updated.get("_links") or {}).get("webui", "")
    print(f"Updated hub id={page_id} webui={webui}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
