#!/usr/bin/env bash
# Build Streammux Docker images, bump VERSION (patch by default), push to GitLab Container Registry.
#
# Primary path: GitLab CI (.gitlab-ci.yml) builds and pushes on every branch/tag push
# using CI_REGISTRY_* credentials. Use this script for manual semver releases.
#
# Base image sources (DOCKER_HUB_PROXY build-arg prefix in Dockerfiles):
#   1. Direct Docker Hub (default, matches CI): DOCKER_HUB_PROXY="" (empty).
#      Requires outbound docker.io access; run `docker login docker.io` if pulls fail.
#   2. GitLab dependency proxy (optional): export DOCKER_HUB_PROXY="${IMAGE_REPO}/dependency_proxy/containers/"
#      Requires dependency proxy enabled on the GitLab project and `docker login registry.gitlab.com`
#      with read_registry. This streammux project does NOT have dependency proxy enabled.
#   3. Internal mirror registry (orbgny / air-gapped deploy hosts only): base images must be
#      referenced by full mirror path in Dockerfiles — see devops inventory/group_vars/all/mirror-images.yml
#      and scripts/mirror-images-to-gitlab.sh. There is no single-prefix mirror for eclipse-temurin/node;
#      nginx:1.27-alpine is mirrored; temurin/node are not yet in the manifest.
#
# Prerequisites:
#   docker login registry.gitlab.com   (PAT with read_registry + write_registry; needed to push)
#
# Usage:
#   export IMAGE_REPO=registry.gitlab.com/dmr4013905/techarchitecture/techarchitecture/streammux
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
IMAGE_REPO="${IMAGE_REPO:-registry.gitlab.com/dmr4013905/techarchitecture/techarchitecture/streammux}"
# Match .gitlab-ci.yml: dependency proxy is not enabled on this project; pull Hub directly.
DEFAULT_DOCKER_HUB_PROXY=""
DEPENDENCY_PROXY_PREFIX="${IMAGE_REPO}/dependency_proxy/containers/"
if [[ -z "${DOCKER_HUB_PROXY+x}" ]]; then
  DOCKER_HUB_PROXY="$DEFAULT_DOCKER_HUB_PROXY"
fi

BASE_PROBE_IMAGE="eclipse-temurin:21-jdk@sha256:da9d3a4f7650db39b918fc5a2c3da76556fb8cc8e5f3767cdea0bb409286951a"

preflight_base_image_source() {
  local ref="${DOCKER_HUB_PROXY}${BASE_PROBE_IMAGE}"
  if docker pull "$ref" >/dev/null 2>&1; then
    echo "Base image source OK: ${DOCKER_HUB_PROXY:-docker.io (direct)}"
    return 0
  fi

  echo "Failed to pull probe base image: ${ref}" >&2

  if [[ -n "$DOCKER_HUB_PROXY" && "$DOCKER_HUB_PROXY" == "$DEPENDENCY_PROXY_PREFIX" ]]; then
    cat >&2 <<EOF
Hint: GitLab dependency proxy is not enabled for this project (see .gitlab-ci.yml).
  export DOCKER_HUB_PROXY=
  docker login docker.io   # if anonymous pulls fail
EOF
  elif [[ -n "$DOCKER_HUB_PROXY" ]]; then
    cat >&2 <<EOF
Hint: custom DOCKER_HUB_PROXY prefix may be wrong or you lack registry auth.
  export DOCKER_HUB_PROXY=   # direct Docker Hub (default)
  docker login registry.gitlab.com   # for dependency proxy or push
  docker login docker.io             # for direct Hub pulls
EOF
  else
    cat >&2 <<EOF
Hint: direct Docker Hub pull failed. Try:
  docker login docker.io
  docker pull ${BASE_PROBE_IMAGE}
If you are on a host without docker.io access, mirror bases via devops/scripts/mirror-images-to-gitlab.sh
and build on a machine with Hub access, or ask ops to add eclipse-temurin/node to mirror-images.yml.
EOF
  fi
  exit 1
}

preflight_registry_push_auth() {
  [[ "$DO_PUSH" -eq 1 ]] || return 0
  local registry_host="${REPO%%/*}"
  if docker pull "${REPO}/job-management-api:latest" >/dev/null 2>&1; then
    return 0
  fi
  if ! grep -q "$registry_host" "${HOME}/.docker/config.json" 2>/dev/null; then
    echo "Warning: no docker credentials for ${registry_host}; push may fail." >&2
    echo "  docker login ${registry_host}" >&2
  fi
}

BASE_BUILD_ARGS=(--build-arg "DOCKER_HUB_PROXY=${DOCKER_HUB_PROXY}")
API_IMAGE_NAME="${STREAMMUX_API_IMAGE_NAME:-job-management-api}"
ORCH_IMAGE_NAME="${STREAMMUX_ORCH_IMAGE_NAME:-site-orchestrator}"
WEB_IMAGE_NAME="${STREAMMUX_WEB_IMAGE_NAME:-web-ui}"
CATALOG_IMAGE_NAME="${STREAMMUX_CATALOG_IMAGE_NAME:-job-catalog-api}"

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

if [[ -z "$IMAGE_REPO" && "$DRY_RUN" -eq 0 ]]; then
  echo "Set IMAGE_REPO to your GitLab container registry path." >&2
  exit 1
fi

REPO="${IMAGE_REPO:-registry.gitlab.com/your-group/your-project}"

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
API_TAG="${REPO}/${API_IMAGE_NAME}:${NEW_VER}"
ORCH_TAG="${REPO}/${ORCH_IMAGE_NAME}:${NEW_VER}"
WEB_TAG="${REPO}/${WEB_IMAGE_NAME}:${NEW_VER}"
CATALOG_TAG="${REPO}/${CATALOG_IMAGE_NAME}:${NEW_VER}"
API_LATEST="${REPO}/${API_IMAGE_NAME}:latest"
ORCH_LATEST="${REPO}/${ORCH_IMAGE_NAME}:latest"
WEB_LATEST="${REPO}/${WEB_IMAGE_NAME}:latest"
CATALOG_LATEST="${REPO}/${CATALOG_IMAGE_NAME}:latest"

echo "Last version (from ${VERSION_FILE}): ${CURRENT}"
echo "New version:                         ${NEW_VER}"
echo "API image:                             ${API_TAG}"
echo "Orchestrator image:                    ${ORCH_TAG}"
echo "Web UI image:                          ${WEB_TAG}"
echo "Job catalog API image:                 ${CATALOG_TAG}"
echo "Docker Hub proxy prefix:               ${DOCKER_HUB_PROXY:-<direct docker.io>}"

if [[ "$DRY_RUN" -eq 1 ]]; then
  exit 0
fi

preflight_base_image_source
preflight_registry_push_auth

docker build -f Dockerfile.api "${BASE_BUILD_ARGS[@]}" -t "$API_TAG" -t "$API_LATEST" "$ROOT"
docker build -f Dockerfile.orchestrator "${BASE_BUILD_ARGS[@]}" -t "$ORCH_TAG" -t "$ORCH_LATEST" "$ROOT"
WEB_DOCKER_ARGS=()
# Bake-time default for the "New job" template field. Inlined into the JS bundle
# by Vite — do NOT set when building images for public registries, or you will
# leak the internal Kafka hostname to anyone who pulls the image.
if [[ -n "${VITE_EXAMPLE_KAFKA_BOOTSTRAP:-}" ]]; then
  WEB_DOCKER_ARGS+=(--build-arg "VITE_EXAMPLE_KAFKA_BOOTSTRAP=${VITE_EXAMPLE_KAFKA_BOOTSTRAP}")
fi
docker build -f Dockerfile.web "${BASE_BUILD_ARGS[@]}" "${WEB_DOCKER_ARGS[@]}" -t "$WEB_TAG" -t "$WEB_LATEST" "$ROOT"
docker build -f Dockerfile.catalog "${BASE_BUILD_ARGS[@]}" -t "$CATALOG_TAG" -t "$CATALOG_LATEST" "$ROOT"

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
