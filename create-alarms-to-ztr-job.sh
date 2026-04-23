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
    \"jobId\": \"alarms-to-ztr-poc-1\",
    \"jobVersion\": 0,
    \"jobType\": \"ALARMS_TO_ZTR\",
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
    \"alarmsToZtrConfig\": {
      \"inputTopic\": \"net.optimum.monitoring.nokia.raw-alarms\",
      \"outputTopic\": \"lab.optimum.experimental.streamlens.streammux.nokia-ztr\",
      \"source\": \"nokia\",
      \"sampleRate\": 1.0,
      \"defaultMappingName\": \"default\",
      \"mappings\": {
        \"default\": {
          \"schema_version\": \"2.0\",
          \"event_id\": \"\$input.alarm.id\",
          \"event_type\": {
            \"\$input\": \"alarm.severity\",
            \"\$map\": { \"CLEARED\": \"CLEAR\", \"default\": \"NEW\" }
          },
          \"correlation_key\": \"\$input.extension.source|\$input.alarm.key\",
          \"dedupe_hint\": \"\$input.extension.source|\$input.alarm.key\",
          \"detected_at\": \"\$input.time\",
          \"last_changed_at\": \"\$input.time\",
          \"source_system\": \"\$input.extension.source\",
          \"source_event_id\": \"\$input.alarm.id\",
          \"environment\": \"prod\",
          \"business_severity\": 3,
          \"technical_severity\": \"\$input.alarm.severity\",
          \"utt_issueSummary\": \"\$input.alarm.name\",
          \"utt_workLogDetails\": \"\$input.alarm.text\",
          \"utt_issueCode\": \"\$input.alarm.key\",
          \"utt_symptomCode\": \"\$input.alarm.probable-cause\",
          \"actionability_isActionable\": true,
          \"actionability_reason\": \"From alarm stream\",
          \"category_detectionMethod\": \"log-monitoring\",
          \"labels\": [\"\$input.extension.source\", \"prod\"]
        },
        \"configmap\": {
          \"schema_version\": \"2.0\",
          \"event_id\": \"\$input.alarm.id\",
          \"event_type\": \"NEW\",
          \"utt_issueCode\": \"\$input.alarm.key\",
          \"category_problemCode\": \"config-change\"
        }
      },
      \"filter\": {
        \"defaultMappingName\": \"default\",
        \"rules\": [
          { \"path\": \"alarm.severity\", \"op\": \"in\", \"values\": [\"CRITICAL\", \"MAJOR\"] },
          { \"path\": \"alarm.key\", \"op\": \"regex\", \"value\": \"^CONFIG\", \"mappingName\": \"configmap\" }
        ]
      },
      \"streamProperties\": {
        \"bootstrap.servers\": \"${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}\",
        \"auto.offset.reset\": \"earliest\"
      }
    },
    \"labels\": {},
    \"tags\": [\"poc\", \"alarms\"],
    \"updatedAt\": null,
    \"updatedBy\": \"local-dev\"
  }"
