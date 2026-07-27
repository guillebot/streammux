#!/usr/bin/env bash
# Posts an ALARMS_TO_ZTR job that reads Alarm Manager on Rednet and drops massive
# FTTH Agora ONU / HFC cable-modem noise using the filter in examples/alarmmanager-noise-filter.json.
#
# Filter semantics: ordered allowlist — first matching rule forwards; no match drops.
# Tune subsystem/specificProblem lists after validating against your probe inventory.
#
# Usage:
#   ./create-alarmmanager-noise-filter-job.sh
#   JOB_ID=alarmmanager-noise-filter OUTPUT_TOPIC=lab.optimum... ./create-alarmmanager-noise-filter-job.sh

set -euo pipefail

if [[ -f ".env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source ".env"
  set +a
fi

JOB_ID="${JOB_ID:-alarmmanager-noise-filter}"
OUTPUT_TOPIC="${OUTPUT_TOPIC:-net.optimum.experimental.streamlens.streammux.alarmmanager.filtered}"
API_PORT="${JOB_MANAGEMENT_API_PORT:-8080}"

curl -sS -X POST "http://localhost:${API_PORT}/jobs" \
  -H 'Content-Type: application/json' \
  -d "{
    \"jobId\": \"${JOB_ID}\",
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
      \"inputTopic\": \"com.optimum.monitoring.alarmmanager.alarms\",
      \"outputTopic\": \"${OUTPUT_TOPIC}\",
      \"source\": \"alarm-manager\",
      \"sampleRate\": 1.0,
      \"defaultMappingName\": \"passthrough\",
      \"mappings\": {
        \"passthrough\": {
          \"schema_version\": \"alarm-manager-1\",
          \"id\": \"\$input.id\",
          \"eventType\": \"\$input.eventType\",
          \"severity\": \"\$input.severity\",
          \"urgency\": \"\$input.urgency\",
          \"aiState\": \"\$input.aiState\",
          \"subsystem\": \"\$input.subsystem\",
          \"domain\": \"\$input.domain\",
          \"specificProblem\": \"\$input.specificProblem\",
          \"summary\": \"\$input.summary\",
          \"node\": \"\$input.node\",
          \"nodeAlias\": \"\$input.nodeAlias\",
          \"managedObjectClass\": \"\$input.managedObjectClass\",
          \"managedObjectInstance\": \"\$input.managedObjectInstance\",
          \"technology\": \"\$input.technology\",
          \"probableCause\": \"\$input.probableCause\",
          \"alarmType\": \"\$input.alarmType\",
          \"systemCreationTime\": \"\$input.systemCreationTime\",
          \"systemLastUpdateTime\": \"\$input.systemLastUpdateTime\",
          \"startTime\": \"\$input.startTime\",
          \"lastEndTime\": \"\$input.lastEndTime\"
        }
      },
      \"filter\": {
        \"defaultMappingName\": \"passthrough\",
        \"rules\": [
          {
            \"path\": \"subsystem\",
            \"op\": \"not_in\",
            \"values\": [
              \"FTTH-AGORA-SNMP\",
              \"HFC-CM-SNMP\",
              \"HFC-DOCSIS-SNMP\",
              \"HFC-CMTS-SNMP\",
              \"SDL-HFC-SNMP\",
              \"OPT-HFC-SNMP\"
            ],
            \"mappingName\": \"passthrough\"
          },
          {
            \"path\": \"specificProblem\",
            \"op\": \"not_in\",
            \"values\": [
              \"Loss of signal for ONUi\",
              \"Receive dying-gasp of ONUi\",
              \"Signal degraded of ONUi\",
              \"Start-up failure of ONUi\",
              \"Deactivate failure of ONUi\",
              \"RX GPON optical signal above rated value\",
              \"RX GPON optical signal below rated value\",
              \"New ONT Detected\"
            ],
            \"mappingName\": \"passthrough\"
          },
          {
            \"path\": \"managedObjectClass\",
            \"op\": \"not_in\",
            \"values\": [\"ONU\", \"PON\"],
            \"mappingName\": \"passthrough\"
          },
          {
            \"path\": \"technology\",
            \"op\": \"ne\",
            \"value\": \"GPON\",
            \"mappingName\": \"passthrough\"
          },
          {
            \"path\": \"managedObjectClass\",
            \"op\": \"not_in\",
            \"values\": [\"CableModem\", \"CM\", \"MTA\", \"docsCableMaclayer\"],
            \"mappingName\": \"passthrough\"
          },
          {
            \"path\": \"specificProblem\",
            \"op\": \"not_in\",
            \"values\": [
              \"CM Status Change\",
              \"Modem Offline\",
              \"Modem Online\",
              \"CM On/offline\",
              \"UsChannelSNRAlarm\",
              \"DsChannelSNRAlarm\",
              \"Power Adjustment\"
            ],
            \"mappingName\": \"passthrough\"
          }
        ]
      },
      \"streamProperties\": {
        \"bootstrap.servers\": \"${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}\",
        \"auto.offset.reset\": \"latest\"
      }
    },
    \"labels\": {},
    \"tags\": [\"alarm-manager\", \"noise-filter\"],
    \"updatedAt\": null,
    \"updatedBy\": \"local-dev\"
  }"

echo
echo "Posted job ${JOB_ID} -> ${OUTPUT_TOPIC}"
