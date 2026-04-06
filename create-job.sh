#!/usr/bin/env bash

set -euo pipefail

if [[ -f ".env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source ".env"
  set +a
fi

curl -X POST "http://localhost:${JOB_MANAGEMENT_API_PORT:-8080}/jobs" \
  -H 'Content-Type: application/json' \
  -d "{
    \"jobId\": \"route-poc-1\",
    \"jobVersion\": 0,
    \"jobType\": \"ROUTE_APP\",
    \"desiredState\": \"ACTIVE\",
    \"priority\": 1,
    \"siteAffinity\": \"${STREAMMUX_SITE_ID:-site-a}\",
    \"leasePolicy\": {
      \"heartbeatIntervalSeconds\": 10,
      \"leaseDurationSeconds\": 30,
      \"claimBackoffMillis\": 5000,
      \"allowFailover\": true
    },
    \"parallelism\": 1,
    \"routeAppConfig\": {
      \"inputTopic\": \"net.optimum.monitoring.netscout.mobile.sip.json\",
      \"inputFormat\": \"JSON\",
      \"outputFormat\": \"JSON\",
      \"protobufSchemaSubject\": null,
      \"routes\": [
        {
          \"routeId\": \"contains-foo\",
          \"filterExpression\": \"application_name == \\\"SIP_TCP\\\"\",
          \"outputTopic\": \"lab.optimum.experimental.streamlens.streammux.output1\"
        },
        {
          \"routeId\": \"contains-bar\",
          \"filterExpression\": \"bar\",
          \"outputTopic\": \"lab.optimum.experimental.streamlens.streammux.output2\"
        }
      ],
      \"streamProperties\": {
        \"bootstrap.servers\": \"${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}\",
        \"auto.offset.reset\": \"earliest\"
      },
      \"serdeProperties\": {}
    },
    \"labels\": {},
    \"tags\": [\"poc\"],
    \"updatedAt\": null,
    \"updatedBy\": \"local-dev\"
  }"
