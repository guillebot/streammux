#!/usr/bin/env bash

set -euo pipefail

if [[ -f ".env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source ".env"
  set +a
fi

# Sample JSON_ENRICHER job: CSG OSP events joined to custdata-by-acctnum.
# Output remains Restricted if the lookup/input are Restricted — do not log payloads.
curl -X POST "http://localhost:${JOB_MANAGEMENT_API_PORT:-8080}/jobs" \
  -H 'Content-Type: application/json' \
  -d @- <<EOF
{
  "jobId": "json-enricher-csg-osp-1",
  "jobVersion": 0,
  "jobType": "JSON_ENRICHER",
  "desiredState": "ACTIVE",
  "priority": 1,
  "siteAffinity": "${STREAMMUX_SITE_ID:-site-a}",
  "leasePolicy": {
    "heartbeatIntervalSeconds": 10,
    "leaseDurationSeconds": 30,
    "claimBackoffMillis": 5000,
    "allowFailover": true
  },
  "parallelism": 1,
  "jsonEnricherConfig": {
    "inputTopic": "com.optimum.events.it.csg.osp.json",
    "outputTopic": "net.optimum.experimental.streamlens.streammux.csg-osp.enriched.json",
    "source": "csg",
    "joinKeyPath": "AccountNum",
    "joinKeyCel": "size(key.split(\"-\")) == 3 ? key.split(\"-\")[0] + key.split(\"-\")[1] + (size(key.split(\"-\")[2]) >= 2 ? key.split(\"-\")[2] : \"0\" + key.split(\"-\")[2]) : key",
    "lookupTopic": "net.optimum.fixed.monitoring.network.access.custdata.acctnum.json",
    "enrichmentName": "custdata",
    "streamProperties": {
      "bootstrap.servers": "${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
    }
  }
}
EOF
echo
