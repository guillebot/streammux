#!/bin/sh
set -e
if [ -z "${STREAMMUX_JOB_MANAGEMENT_API:-}" ]; then
  echo "streammux web-ui: STREAMMUX_JOB_MANAGEMENT_API must be set (host:port for job-management-api upstream)" >&2
  exit 1
fi
if [ -z "${STREAMMUX_JOB_CATALOG_API:-}" ]; then
  echo "streammux web-ui: STREAMMUX_JOB_CATALOG_API must be set (host:port for job-catalog-api upstream)" >&2
  exit 1
fi
echo "streammux web-ui: upstream job-management-api=${STREAMMUX_JOB_MANAGEMENT_API} job-catalog-api=${STREAMMUX_JOB_CATALOG_API}"
