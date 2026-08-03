#!/bin/sh
set -e
if [ -z "${STREAMMUX_API_USERNAME:-}" ] || [ -z "${STREAMMUX_API_PASSWORD:-}" ]; then
  echo "streammux web-ui: STREAMMUX_API_USERNAME and STREAMMUX_API_PASSWORD must be set (job-management-api basic auth)" >&2
  exit 1
fi
if [ -z "${MCP_ADMIN_TOKEN:-}" ]; then
  echo "streammux web-ui: MCP_ADMIN_TOKEN must be set (shared secret for /mcp-admin → MCP /admin)" >&2
  exit 1
fi
# Restrict charset so the value is safe inside the nginx conf and sed replacement.
case "${MCP_ADMIN_TOKEN}" in
  *[!A-Za-z0-9._-]*)
    echo "streammux web-ui: MCP_ADMIN_TOKEN must be [A-Za-z0-9._-] only (e.g. openssl rand -hex 32)" >&2
    exit 1
    ;;
esac
if [ "${#MCP_ADMIN_TOKEN}" -lt 24 ]; then
  echo "streammux web-ui: MCP_ADMIN_TOKEN must be at least 24 characters" >&2
  exit 1
fi
AUTH_B64="$(printf '%s' "${STREAMMUX_API_USERNAME}:${STREAMMUX_API_PASSWORD}" | base64 | tr -d '\n')"
sed -i "s|#__STREAMMUX_JOBS_AUTH__|proxy_set_header Authorization \"Basic ${AUTH_B64}\";|g" /etc/nginx/conf.d/default.conf
sed -i "s|#__STREAMMUX_MCP_ADMIN_TOKEN__|proxy_set_header X-Streammux-Mcp-Admin-Token \"${MCP_ADMIN_TOKEN}\";|g" /etc/nginx/conf.d/default.conf
