#!/usr/bin/env bash
# (c) Optimum 2026
# Guillermo Schimmel
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="${ROOT}/scripts/build-app-version.sh"

assert_eq() {
  local name="$1"
  local expected="$2"
  local actual="$3"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "FAIL ${name}: expected '${expected}', got '${actual}'" >&2
    exit 1
  fi
  echo "ok ${name}"
}

assert_eq "release tag" "20260825-03" "$(RELEASE_TAG=20260825-03 bash "${SCRIPT}")"
assert_eq "commit tag" "20260825-07" "$(CI_COMMIT_TAG=20260825-07 bash "${SCRIPT}")"
assert_eq "pipeline 1" "20260722-00" "$(RELEASE_DATE=20260722 CI_PIPELINE_IID=1 bash "${SCRIPT}")"
assert_eq "pipeline 2" "20260722-01" "$(RELEASE_DATE=20260722 CI_PIPELINE_IID=2 bash "${SCRIPT}")"
assert_eq "pipeline 100" "20260722-99" "$(RELEASE_DATE=20260722 CI_PIPELINE_IID=100 bash "${SCRIPT}")"
assert_eq "pipeline 101 wraps" "20260722-00" "$(RELEASE_DATE=20260722 CI_PIPELINE_IID=101 bash "${SCRIPT}")"

echo "All build-app-version tests passed."
