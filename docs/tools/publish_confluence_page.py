#!/usr/bin/env python3
"""
Convert docs/STREAMMUX.confluence.md to Confluence storage (XHTML) and create or update
a page via Confluence Cloud REST API v1.

Authentication: Basic auth with ATLASSIAN_EMAIL + ATLASSIAN_API_TOKEN (never commit tokens).

Usage:
  export ATLASSIAN_EMAIL='you@company.com'
  export ATLASSIAN_API_TOKEN='your-api-token'
  python3 docs/tools/publish_confluence_page.py [path/to.md]

Env overrides:
  CONFLUENCE_HOST   default oneoptimum.atlassian.net
  CONFLUENCE_SPACE  default ~gschimme
"""
from __future__ import annotations

import base64
import html
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import List, Optional, Tuple


def _inline_markup(text: str) -> str:
    """Escape then apply **bold**, *italic*, `code` (code segments first so italics can wrap them)."""
    text = html.escape(text)
    placeholders: List[str] = []

    def code_repl(m: re.Match[str]) -> str:
        inner = m.group(1)
        placeholders.append("<code>" + inner + "</code>")
        return f"\x00{len(placeholders) - 1}\x00"

    text = re.sub(r"`([^`]+)`", code_repl, text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)", r"<em>\1</em>", text)
    for i, ph in enumerate(placeholders):
        text = text.replace(f"\x00{i}\x00", ph)
    return text


def _table_row(cells: List[str], header: bool) -> str:
    tag = "th" if header else "td"
    inner = "".join(f"<{tag}><p>{_inline_markup(c.strip())}</p></{tag}>" for c in cells)
    return f"<tr>{inner}</tr>"


def markdown_to_storage(md: str) -> Tuple[str, str]:
    """
    Returns (title, storage_xhtml). First line must be '# Title'.
    """
    lines = md.replace("\r\n", "\n").split("\n")
    if not lines or not lines[0].startswith("# "):
        raise ValueError("Expected first line '# Title'")
    title = lines[0][2:].strip()
    body_lines = lines[1:]

    out: List[str] = []
    i = 0
    para: List[str] = []

    def flush_para() -> None:
        nonlocal para
        if not para:
            return
        text = " ".join(para).strip()
        if text:
            out.append(f"<p>{_inline_markup(text)}</p>")
        para = []

    while i < len(body_lines):
        line = body_lines[i]
        stripped = line.strip()

        if stripped == "":
            flush_para()
            i += 1
            continue

        if stripped == "---":
            flush_para()
            out.append("<hr/>")
            i += 1
            continue

        if stripped.startswith("#"):
            flush_para()
            m = re.match(r"^(#{1,6})\s+(.*)$", stripped)
            if not m:
                para.append(stripped)
                i += 1
                continue
            level = len(m.group(1))
            content = m.group(2).strip()
            out.append(f"<h{level}>{_inline_markup(content)}</h{level}>")
            i += 1
            continue

        if stripped.startswith("|") and "|" in stripped[1:]:
            flush_para()
            table_lines: List[str] = []
            while i < len(body_lines) and body_lines[i].strip().startswith("|"):
                table_lines.append(body_lines[i].strip())
                i += 1
            if len(table_lines) >= 2 and re.match(r"^\|\s*[-:]+\s*(\|\s*[-:]+\s*)+\|?$", table_lines[1]):
                header = table_lines[0]
                data_rows = table_lines[2:]
            else:
                header = table_lines[0]
                data_rows = table_lines[1:]

            def split_row(r: str) -> List[str]:
                r = r.strip()
                if r.startswith("|"):
                    r = r[1:]
                if r.endswith("|"):
                    r = r[:-1]
                return [c.strip() for c in r.split("|")]

            rows_html: List[str] = []
            hcells = split_row(header)
            rows_html.append(_table_row(hcells, header=True))
            for dr in data_rows:
                if not dr.strip("| \t"):
                    continue
                rows_html.append(_table_row(split_row(dr), header=False))
            out.append("<table><tbody>" + "".join(rows_html) + "</tbody></table>")
            continue

        if stripped.startswith("- "):
            flush_para()
            items: List[str] = []
            while i < len(body_lines) and body_lines[i].strip().startswith("- "):
                items.append(body_lines[i].strip()[2:].strip())
                i += 1
            lis = "".join(f"<li><p>{_inline_markup(it)}</p></li>" for it in items)
            out.append(f"<ul>{lis}</ul>")
            continue

        if re.match(r"^\d+\.\s+", stripped):
            flush_para()
            items = []
            while i < len(body_lines):
                s = body_lines[i].strip()
                mnum = re.match(r"^\d+\.\s+(.*)$", s)
                if not mnum:
                    break
                items.append(mnum.group(1).strip())
                i += 1
            lis = "".join(f"<li><p>{_inline_markup(it)}</p></li>" for it in items)
            out.append(f"<ol>{lis}</ol>")
            continue

        para.append(stripped)
        i += 1

    flush_para()
    return title, "".join(out)


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


def main() -> int:
    host = os.environ.get("CONFLUENCE_HOST", "oneoptimum.atlassian.net").rstrip("/")
    space_key = os.environ.get("CONFLUENCE_SPACE", "~gschimme")
    email = os.environ.get("ATLASSIAN_EMAIL", "").strip()
    token = os.environ.get("ATLASSIAN_API_TOKEN", "").strip()
    if not email or not token:
        print("Set ATLASSIAN_EMAIL and ATLASSIAN_API_TOKEN", file=sys.stderr)
        return 2

    md_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(__file__), "..", "STREAMMUX.confluence.md"
    )
    md_path = os.path.abspath(md_path)
    with open(md_path, encoding="utf-8") as f:
        md = f.read()

    title, storage = markdown_to_storage(md)
    base = f"https://{host}/wiki/rest/api"

    auth = _basic_auth_header(email, token)

    # Verify space
    code, space = _request_json("GET", f"{base}/space/{urllib.parse.quote(space_key, safe='~')}", auth)
    if code != 200:
        print(f"Space lookup failed HTTP {code}: {space}", file=sys.stderr)
        return 1

    # CQL search for existing page by title in space
    safe_title = title.replace("\\", "\\\\").replace('"', '\\"')
    cql = f'type=page AND space="{space_key}" AND title="{safe_title}"'
    search_url = f"{base}/content/search?cql={urllib.parse.quote(cql)}&limit=1"
    code, search = _request_json("GET", search_url, auth)
    if code != 200:
        print(f"Search failed HTTP {code}: {search}", file=sys.stderr)
        return 1

    results = search.get("results") or []
    payload_base = {
        "type": "page",
        "title": title,
        "space": {"key": space_key},
        "body": {
            "storage": {
                "value": storage,
                "representation": "storage",
            }
        },
    }

    if not results:
        code, created = _request_json("POST", f"{base}/content", auth, payload_base)
        if code not in (200, 201):
            print(f"Create failed HTTP {code}: {created}", file=sys.stderr)
            return 1
        webui = (created.get("_links") or {}).get("webui", "")
        print(f"Created page id={created.get('id')} webui={webui}")
        return 0

    page_id = results[0]["id"]
    code, current = _request_json("GET", f"{base}/content/{page_id}?expand=body.storage,version", auth)
    if code != 200:
        print(f"Get page failed HTTP {code}: {current}", file=sys.stderr)
        return 1

    ver = (current.get("version") or {}).get("number", 1)
    update = {
        **payload_base,
        "id": page_id,
        "version": {"number": ver + 1, "message": "Sync from docs/STREAMMUX.confluence.md"},
    }
    code, updated = _request_json("PUT", f"{base}/content/{page_id}", auth, update)
    if code != 200:
        print(f"Update failed HTTP {code}: {updated}", file=sys.stderr)
        return 1
    webui = (updated.get("_links") or {}).get("webui", "")
    print(f"Updated page id={page_id} webui={webui}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
