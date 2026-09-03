#!/usr/bin/env bash
# (c) Optimum 2026
# Guillermo Schimmel
#
# scripts/build-app-version.sh
#
# Compute the Streammux application version baked into the web UI (VITE_APP_VERSION).
# Format: YYYYMMDD-NN (UTC date + 2-digit suffix).
#
# Priority:
#   1. RELEASE_TAG (release:images / build_and_push.sh)
#   2. CI_COMMIT_TAG when it matches YYYYMMDD-NN (release:images:retag)
#   3. GitLab CI pipeline builds: YYYYMMDD-NN from UTC date + CI_PIPELINE_IID (00-99)
#   4. Local fallback: next git release tag (scripts/next-release-tag.sh)
#
# Usage:
#   scripts/build-app-version.sh
#   RELEASE_TAG=20260825-01 scripts/build-app-version.sh
#   RELEASE_DATE=20260722 CI_PIPELINE_IID=3 scripts/build-app-version.sh
#
set -euo pipefail

if [[ -n "${RELEASE_TAG:-}" ]]; then
  echo "${RELEASE_TAG}"
  exit 0
fi

if [[ -n "${CI_COMMIT_TAG:-}" ]] && [[ "${CI_COMMIT_TAG}" =~ ^[0-9]{8}-[0-9]{2}$ ]]; then
  echo "${CI_COMMIT_TAG}"
  exit 0
fi

DATE="${RELEASE_DATE:-$(date -u +%Y%m%d)}"

if [[ -n "${CI_PIPELINE_IID:-}" ]]; then
  # One distinct UI version per pipeline; suffix wraps 00-99 (same limit as release tags).
  seq=$(( (10#${CI_PIPELINE_IID} - 1) % 100 ))
  printf '%s-%02d\n' "${DATE}" "${seq}"
  exit 0
fi

bash "$(dirname "$0")/next-release-tag.sh"
