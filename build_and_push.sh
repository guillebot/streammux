#!/usr/bin/env bash
# Build Streammux Docker images and push to GitLab Container Registry.
#
# Primary release path: GitLab CI manual release:tag + release:images (YYYYMMDD-NN).
# See docs/DEPLOY.md. Use this script for local/emergency builds only.
#
# Base image sources (DOCKER_HUB_PROXY build-arg prefix in Dockerfiles):
#   1. Direct Docker Hub (default, matches CI): DOCKER_HUB_PROXY="" (empty).
#   2. GitLab dependency proxy (optional): export DOCKER_HUB_PROXY="${IMAGE_REPO}/dependency_proxy/containers/"
#   3. Internal mirror registry (orbgny / air-gapped deploy hosts only).
#
# Prerequisites:
#   docker login registry.gitlab.com   (PAT with read_registry + write_registry)
#
# Usage:
#   export IMAGE_REPO=registry.gitlab.com/dmr4013905/techarchitecture/techarchitecture/streammux
#   ./build_and_push.sh                      # next YYYYMMDD-NN from git tags
#   ./build_and_push.sh -v 20260825-01       # explicit release tag
#   ./build_and_push.sh --no-push            # build only
#   ./build_and_push.sh --dry-run            # print tags, no build/push
#
# Optional:
#   RELEASE_TAG=20260825-01 ./build_and_push.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

IMAGE_REPO="${IMAGE_REPO:-registry.gitlab.com/dmr4013905/techarchitecture/techarchitecture/streammux}"
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
  docker login docker.io
EOF
  elif [[ -n "$DOCKER_HUB_PROXY" ]]; then
    cat >&2 <<EOF
Hint: custom DOCKER_HUB_PROXY prefix may be wrong or you lack registry auth.
  export DOCKER_HUB_PROXY=
  docker login registry.gitlab.com
  docker login docker.io
EOF
  else
    cat >&2 <<EOF
Hint: direct Docker Hub pull failed. Try:
  docker login docker.io
  docker pull ${BASE_PROBE_IMAGE}
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
MCP_IMAGE_NAME="${STREAMMUX_MCP_IMAGE_NAME:-mcp}"

DO_PUSH=1
DRY_RUN=0
RELEASE_TAG="${RELEASE_TAG:-}"

usage() {
  sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -v|--version)
      [[ $# -ge 2 ]] || { echo "Missing value for $1" >&2; exit 1; }
      RELEASE_TAG="$2"
      shift 2
      ;;
    --no-push) DO_PUSH=0; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "Unknown option: $1" >&2; usage 1 ;;
  esac
done

if [[ -z "$RELEASE_TAG" ]]; then
  RELEASE_TAG="$(bash "${ROOT}/scripts/next-release-tag.sh")"
fi

if [[ ! "$RELEASE_TAG" =~ ^[0-9]{8}-[0-9]{2}$ ]]; then
  echo "ERROR: release tag must match YYYYMMDD-NN, got: ${RELEASE_TAG}" >&2
  exit 1
fi

if [[ -z "$IMAGE_REPO" && "$DRY_RUN" -eq 0 ]]; then
  echo "Set IMAGE_REPO to your GitLab container registry path." >&2
  exit 1
fi

REPO="${IMAGE_REPO:-registry.gitlab.com/your-group/your-project}"

echo "Release tag:                           ${RELEASE_TAG}"
echo "API image:                             ${REPO}/${API_IMAGE_NAME}:${RELEASE_TAG}"
echo "Orchestrator image:                    ${REPO}/${ORCH_IMAGE_NAME}:${RELEASE_TAG}"
echo "Web UI image:                          ${REPO}/${WEB_IMAGE_NAME}:${RELEASE_TAG}"
echo "Job catalog API image:                 ${REPO}/${CATALOG_IMAGE_NAME}:${RELEASE_TAG}"
echo "MCP image:                             ${REPO}/${MCP_IMAGE_NAME}:${RELEASE_TAG}"
echo "Docker Hub proxy prefix:               ${DOCKER_HUB_PROXY:-<direct docker.io>}"

if [[ "$DRY_RUN" -eq 1 ]]; then
  exit 0
fi

preflight_base_image_source
preflight_registry_push_auth

docker build -f Dockerfile.api "${BASE_BUILD_ARGS[@]}" -t "${REPO}/${API_IMAGE_NAME}:${RELEASE_TAG}" "$ROOT"
docker build -f Dockerfile.orchestrator "${BASE_BUILD_ARGS[@]}" -t "${REPO}/${ORCH_IMAGE_NAME}:${RELEASE_TAG}" "$ROOT"
WEB_DOCKER_ARGS=(--build-arg "VITE_APP_VERSION=${RELEASE_TAG}")
if [[ -n "${VITE_EXAMPLE_KAFKA_BOOTSTRAP:-}" ]]; then
  WEB_DOCKER_ARGS+=(--build-arg "VITE_EXAMPLE_KAFKA_BOOTSTRAP=${VITE_EXAMPLE_KAFKA_BOOTSTRAP}")
fi
docker build -f Dockerfile.web "${BASE_BUILD_ARGS[@]}" "${WEB_DOCKER_ARGS[@]}" -t "${REPO}/${WEB_IMAGE_NAME}:${RELEASE_TAG}" "$ROOT"
docker build -f Dockerfile.catalog "${BASE_BUILD_ARGS[@]}" -t "${REPO}/${CATALOG_IMAGE_NAME}:${RELEASE_TAG}" "$ROOT"
docker build -f deploy/go.Dockerfile "${BASE_BUILD_ARGS[@]}" --build-arg SERVICE=mcp -t "${REPO}/${MCP_IMAGE_NAME}:${RELEASE_TAG}" "$ROOT"

if [[ "$DO_PUSH" -eq 1 ]]; then
  docker push "${REPO}/${API_IMAGE_NAME}:${RELEASE_TAG}"
  docker push "${REPO}/${ORCH_IMAGE_NAME}:${RELEASE_TAG}"
  docker push "${REPO}/${WEB_IMAGE_NAME}:${RELEASE_TAG}"
  docker push "${REPO}/${CATALOG_IMAGE_NAME}:${RELEASE_TAG}"
  docker push "${REPO}/${MCP_IMAGE_NAME}:${RELEASE_TAG}"
fi

echo "Built (and pushed) release ${RELEASE_TAG}. Create git tag with: scripts/release-tag.sh"
