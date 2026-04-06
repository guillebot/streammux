#!/usr/bin/env bash

set -euo pipefail

if [[ -f ".env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source ".env"
  set +a
fi

API_PORT="${JOB_MANAGEMENT_API_PORT:-8080}"

curl -sS "http://localhost:${API_PORT}/jobs"
