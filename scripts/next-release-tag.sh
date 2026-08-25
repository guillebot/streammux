#!/usr/bin/env bash
# (c) Optimum 2026
# Guillermo Schimmel

# scripts/next-release-tag.sh
#
# Compute the next Streammux release tag: YYYYMMDD-NN (UTC date + daily counter).
# Reads existing git tags matching ^[0-9]{8}-[0-9]{2}$ on the current remote.
#
# Usage:
#   scripts/next-release-tag.sh              # print tag to stdout
#   scripts/next-release-tag.sh --assign     # also append RELEASE_TAG=... to release.env
#   RELEASE_DATE=20260722 scripts/next-release-tag.sh   # dry-run a specific day
#
set -euo pipefail

DATE="${RELEASE_DATE:-$(date -u +%Y%m%d)}"
TAG_PREFIX="${DATE}-"

git fetch --tags origin 2>/dev/null || true

max=0
while IFS= read -r tag; do
  [[ "$tag" =~ ^${DATE}-([0-9]{2})$ ]] || continue
  seq="${BASH_REMATCH[1]}"
  seq=$((10#$seq))
  if (( seq > max )); then
    max=$seq
  fi
done < <(git tag -l "${TAG_PREFIX}*")

next=$((max + 1))
if (( next > 99 )); then
  echo "ERROR: daily release counter exceeded 99 for ${DATE}" >&2
  exit 1
fi

RELEASE_TAG="${DATE}-$(printf '%02d' "$next")"

if [[ "${1:-}" == "--assign" ]]; then
  env_file="${RELEASE_ENV_FILE:-release.env}"
  echo "RELEASE_TAG=${RELEASE_TAG}" >> "${env_file}"
fi

echo "${RELEASE_TAG}"
