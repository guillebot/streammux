#!/usr/bin/env bash

set -euo pipefail

if [[ -f ".env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source ".env"
  set +a
fi

JOB_ID="${JOB_ID:-route-poc-1}"
API_PORT="${JOB_MANAGEMENT_API_PORT:-8080}"

curl -X DELETE "http://localhost:${API_PORT}/jobs/${JOB_ID}" \
  -H 'Content-Type: application/json'
