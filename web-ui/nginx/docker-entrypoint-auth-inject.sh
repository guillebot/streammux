#!/bin/sh
set -e
if [ -z "${STREAMMUX_API_USERNAME:-}" ] || [ -z "${STREAMMUX_API_PASSWORD:-}" ]; then
  echo "streammux web-ui: STREAMMUX_API_USERNAME and STREAMMUX_API_PASSWORD must be set (job-management-api basic auth)" >&2
  exit 1
fi
AUTH_B64="$(printf '%s' "${STREAMMUX_API_USERNAME}:${STREAMMUX_API_PASSWORD}" | base64 | tr -d '\n')"
sed -i "s|#__STREAMMUX_JOBS_AUTH__|proxy_set_header Authorization \"Basic ${AUTH_B64}\";|g" /etc/nginx/conf.d/default.conf
