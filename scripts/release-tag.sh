#!/usr/bin/env bash
# (c) Optimum 2026
# Guillermo Schimmel

# scripts/release-tag.sh
#
# Create and push an annotated Streammux release git tag (YYYYMMDD-NN).
# Intended for local operator use; CI runs the same steps in release:tag.
#
# Usage:
#   scripts/release-tag.sh [--dry-run] [commit-ish]
#
# Requires push access to origin. Set RELEASE_GIT_PUSH_TOKEN when CI_JOB_TOKEN
# cannot push tags (see docs/DEPLOY.md).
#
set -euo pipefail

DRY_RUN=false
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=true
  shift
fi

TARGET="${1:-HEAD}"
RELEASE_TAG="$(bash "$(dirname "$0")/next-release-tag.sh")"

echo "Next release tag : ${RELEASE_TAG}"
echo "Target commit    : ${TARGET} ($(git rev-parse --short "${TARGET}"))"

if git rev-parse "${RELEASE_TAG}" >/dev/null 2>&1; then
  echo "ERROR: tag ${RELEASE_TAG} already exists" >&2
  exit 1
fi

if [[ "${DRY_RUN}" == "true" ]]; then
  echo "Dry run — no tag created."
  exit 0
fi

git tag -a "${RELEASE_TAG}" -m "Streammux release ${RELEASE_TAG} ($(git rev-parse --short "${TARGET}"))" "${TARGET}"
git push origin "${RELEASE_TAG}"

echo "Pushed ${RELEASE_TAG}"
