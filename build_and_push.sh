#!/usr/bin/env bash
# Build Streammux Docker images, bump VERSION (patch by default), push to Docker Hub.
#
# Prerequisites: docker, docker login to Docker Hub (private repos: create
#   streammux-job-management-api, streammux-site-orchestrator, streammux-web-ui,
#   and streammux-job-catalog-api under your namespace).
#
# Usage:
#   export DOCKERHUB_NAMESPACE=your-dockerhub-username   # optional; defaults to gschimmel
#   ./build_and_push.sh
#   ./build_and_push.sh --minor
#   ./build_and_push.sh --major
#   ./build_and_push.sh --no-push          # build + bump VERSION only
#   ./build_and_push.sh --dry-run          # print tags, no build/push/bump
#
# Optional:
#   VERSION_FILE=path/to/VERSION ./build_and_push.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

VERSION_FILE="${VERSION_FILE:-VERSION}"
NAMESPACE="${DOCKERHUB_NAMESPACE:-${DOCKERHUB_USERNAME:-gschimmel}}"
API_IMAGE_NAME="${STREAMMUX_API_IMAGE_NAME:-streammux-job-management-api}"
ORCH_IMAGE_NAME="${STREAMMUX_ORCH_IMAGE_NAME:-streammux-site-orchestrator}"
WEB_IMAGE_NAME="${STREAMMUX_WEB_IMAGE_NAME:-streammux-web-ui}"
CATALOG_IMAGE_NAME="${STREAMMUX_CATALOG_IMAGE_NAME:-streammux-job-catalog-api}"

DO_PUSH=1
DRY_RUN=0
BUMP=patch

usage() {
  sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --major) BUMP=major; shift ;;
    --minor) BUMP=minor; shift ;;
    --no-push) DO_PUSH=0; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "Unknown option: $1" >&2; usage 1 ;;
  esac
done

if [[ -z "$NAMESPACE" && "$DRY_RUN" -eq 0 ]]; then
  echo "Set DOCKERHUB_NAMESPACE or DOCKERHUB_USERNAME to your Docker Hub user or org." >&2
  exit 1
fi

TAG_NS="${NAMESPACE:-your-dockerhub-namespace}"

read_version() {
  if [[ -f "$VERSION_FILE" ]]; then
    tr -d '[:space:]' < "$VERSION_FILE" | head -n1
  else
    echo "0.0.0"
  fi
}

bump_version() {
  local cur="$1"
  local kind="$2"
  local major minor patch
  IFS='.' read -r major minor patch <<< "${cur//[^0-9.]/}"
  major="${major:-0}"
  minor="${minor:-0}"
  patch="${patch:-0}"
  case "$kind" in
    major) echo "$((major + 1)).0.0" ;;
    minor) echo "${major}.$((minor + 1)).0" ;;
    patch) echo "${major}.${minor}.$((patch + 1))" ;;
  esac
}

CURRENT="$(read_version)"
NEW_VER="$(bump_version "$CURRENT" "$BUMP")"
API_TAG="${TAG_NS}/${API_IMAGE_NAME}:${NEW_VER}"
ORCH_TAG="${TAG_NS}/${ORCH_IMAGE_NAME}:${NEW_VER}"
WEB_TAG="${TAG_NS}/${WEB_IMAGE_NAME}:${NEW_VER}"
CATALOG_TAG="${TAG_NS}/${CATALOG_IMAGE_NAME}:${NEW_VER}"
API_LATEST="${TAG_NS}/${API_IMAGE_NAME}:latest"
ORCH_LATEST="${TAG_NS}/${ORCH_IMAGE_NAME}:latest"
WEB_LATEST="${TAG_NS}/${WEB_IMAGE_NAME}:latest"
CATALOG_LATEST="${TAG_NS}/${CATALOG_IMAGE_NAME}:latest"

echo "Last version (from ${VERSION_FILE}): ${CURRENT}"
echo "New version:                         ${NEW_VER}"
echo "API image:                             ${API_TAG}"
echo "Orchestrator image:                    ${ORCH_TAG}"
echo "Web UI image:                          ${WEB_TAG}"
echo "Job catalog API image:                 ${CATALOG_TAG}"

if [[ "$DRY_RUN" -eq 1 ]]; then
  exit 0
fi

docker build -f Dockerfile.api -t "$API_TAG" -t "$API_LATEST" "$ROOT"
docker build -f Dockerfile.orchestrator -t "$ORCH_TAG" -t "$ORCH_LATEST" "$ROOT"
WEB_DOCKER_ARGS=()
# Bake-time default for the "New job" template field. Inlined into the JS bundle
# by Vite — do NOT set when building images for public registries, or you will
# leak the internal Kafka hostname to anyone who pulls the image.
if [[ -n "${VITE_EXAMPLE_KAFKA_BOOTSTRAP:-}" ]]; then
  WEB_DOCKER_ARGS+=(--build-arg "VITE_EXAMPLE_KAFKA_BOOTSTRAP=${VITE_EXAMPLE_KAFKA_BOOTSTRAP}")
fi
docker build -f Dockerfile.web "${WEB_DOCKER_ARGS[@]}" -t "$WEB_TAG" -t "$WEB_LATEST" "$ROOT"
docker build -f Dockerfile.catalog -t "$CATALOG_TAG" -t "$CATALOG_LATEST" "$ROOT"

if [[ "$DO_PUSH" -eq 1 ]]; then
  docker push "$API_TAG"
  docker push "$API_LATEST"
  docker push "$ORCH_TAG"
  docker push "$ORCH_LATEST"
  docker push "$WEB_TAG"
  docker push "$WEB_LATEST"
  docker push "$CATALOG_TAG"
  docker push "$CATALOG_LATEST"
fi

printf '%s\n' "$NEW_VER" > "$VERSION_FILE"
echo "Wrote ${VERSION_FILE} -> ${NEW_VER}"
