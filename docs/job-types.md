# Job types

Streammux supports pluggable **job runners**. Each `JobDefinition` includes a `jobType` and a type-specific config block. The site orchestrator resolves the matching runner and starts it when the orchestrator holds the job lease.

| Job type | Config field | Runner module |
| -------- | ------------ | ------------- |
| `ROUTE_APP` | `routeAppConfig` | `runners/job-runner-route-app` |
| `RANDOM_SAMPLER` | `randomSamplerConfig` | `runners/job-runner-random-sampler` |
| `ALARMS_TO_ZTR` | `alarmsToZtrConfig` | `runners/job-runner-alarms-to-ztr` |
| `JSON_ENRICHER` | `jsonEnricherConfig` | `runners/job-runner-json-enricher` |

All job types share common definition fields: `jobId`, `desiredState` (`ACTIVE`, `PAUSED`, etc.), `leasePolicy`, `parallelism`, `labels`, `tags`.

Topic names in config are validated against `STREAMMUX_ALLOWED_*` allowlists when configured on job-management-api.

---

## ROUTE_APP

A Kafka Streams application that reads from configured input topics, applies **per-route filter expressions**, and writes matching records to output topics. A single input record can match multiple routes and be forwarded to multiple outputs.

### Config shape (`routeAppConfig`)

- `inputTopic` — source topic
- `routes[]` — each route has `outputTopic`, `filterExpression`, optional metadata
- `streamProperties` — Kafka Streams / consumer settings (must include valid `bootstrap.servers` for the runner environment)

### Filter expressions

Each route applies its own `filterExpression` to the incoming payload.

**Compound boolean syntax** — combine comparisons with `&&`, `||`, `!`, and parentheses:

```text
eventType == "NEW" && !(subsystem == "FTTH-AGORA-SNMP" && specificProblem in ["Loss of signal for ONUi", "Receive dying-gasp of ONUi"])
```

**Field comparison mode** — use `==`, `!=`, `in`, or `not in` with JSON Pointer paths (`/message/type`) or dotted paths (`message.type`, `items[0].id`). The right-hand value is parsed as JSON when possible:

```text
message.type == "ALARM"
severity == 3
active == true
/items/0/id != "abc"
specificProblem in ["Loss of signal for ONUi", "Receive dying-gasp of ONUi"]
```

If the right-hand value is not valid JSON, it is treated as a string. Single-quoted and double-quoted strings are accepted.

**Regex mode** — `=~` (matches) / `!~` (does not match) against a regex string. Matching is unanchored (`Matcher.find()`, consistent with the `ALARMS_TO_ZTR` `regex` op), so anchor with `^`/`$` for a full-value match. The path must resolve to a scalar value; missing or non-scalar paths do not match. An invalid regex makes the whole expression unparseable and falls back to substring matching.

```text
subsystem =~ "^FTTH-"
specificProblem !~ "ONUi$"
node =~ "^olt-(chi|nyc)-[0-9]+$"
```

**Substring fallback** — when the expression does not parse as a filter expression, matching uses substring search on the normalized payload text:

```text
Message
error_code=42
```

**Payload normalization:**

- JSON input is parsed directly.
- Protobuf input is converted to JSON first.
- Blank or null `filterExpression` values do not match anything.
- If the path does not exist in field-comparison mode, the expression does not match.

### Example

```bash
./create-job.sh   # posts a sample ROUTE_APP job (route-poc-1)
```

---

## RANDOM_SAMPLER

Probabilistically forwards records from one input topic to one output topic. Useful for load reduction, lab sampling, and integration tests.

### Config shape (`randomSamplerConfig`)

| Field | Type | Meaning |
| ----- | ---- | ------- |
| `inputTopic` | string | Source topic |
| `outputTopic` | string | Destination topic |
| `rate` | number | Probability in **[0, 1]** that each record is forwarded (e.g. `0.01` ≈ 1%, not `1` for 1%) |
| `streamProperties` | map | Kafka Streams settings |

### Web UI note

The Job Builder exposes **sample percent** (0–100) and converts to `rate` by dividing by 100.

### Example job snippet

```json
{
  "jobId": "sampler-lab-1",
  "jobType": "RANDOM_SAMPLER",
  "desiredState": "ACTIVE",
  "randomSamplerConfig": {
    "inputTopic": "net.optimum.monitoring.example.input",
    "outputTopic": "lab.optimum.experimental.streammux.sampled",
    "rate": 0.05,
    "streamProperties": {
      "bootstrap.servers": "kafka:9092",
      "auto.offset.reset": "earliest"
    }
  }
}
```

---

## ALARMS_TO_ZTR

Normalizes JSON alarm payloads into a ZTR-oriented output shape using **inline mapping templates** and optional **filter rules**. Each input record is evaluated against ordered filter rules; the selected mapping template transforms the record.

### Config shape (`alarmsToZtrConfig`)

| Field | Type | Meaning |
| ----- | ---- | ------- |
| `inputTopic` | string | Alarm source topic |
| `outputTopic` | string | Normalized output topic |
| `source` | string | Source system label (e.g. `nokia`) |
| `sampleRate` | number | Optional subsampling rate in [0, 1] |
| `mappings` | map | Named mapping templates (output JSON shape with `$input.<path>` references) |
| `defaultMappingName` | string | Mapping used when no filter rule overrides |
| `filter` | object | Ordered rules with optional per-rule `mappingName` override |
| `streamProperties` | map | Kafka Streams settings |

### Mapping template syntax

Mapping values can be:

- Literal strings/numbers/booleans
- **`$input.alarm.id`** — pull a field from the input JSON
- **`{"$input": "alarm.severity", "$map": {"CLEARED": "CLEAR", "default": "NEW"}}`** — map input values to output values

### Filter rules

Rules are evaluated in order. Supported operators:

| Op | Meaning |
| -- | ------- |
| `eq` | Equal to `value` |
| `ne`, `not_eq` | Not equal |
| `in` | Value in `values` list |
| `not_in` | Value not in `values` list |
| `regex` | String matches regex `value` |
| `exists` | Field presence (`value` true/false) |

Each rule may set `mappingName` to override which mapping template is applied when the rule matches.

### Example

```bash
./create-alarms-to-ztr-job.sh   # posts alarms-to-ztr-poc-1 with sample mappings
```

See also the sample payload in [create-alarms-to-ztr-job.sh](../create-alarms-to-ztr-job.sh) and the contract in `job-contracts/.../AlarmsToZtrConfig.java`.

---

## JSON_ENRICHER

Left-joins a JSON event stream against a lookup topic materialized as a Kafka Streams **GlobalKTable**. A field is extracted from each input value, normalized with **CEL**, and used as the exact string lookup key. The runner emits a wrapped JSON envelope; it does not mutate the original payload in place.

This is a generic enricher. A typical first job is CSG OSP work-order events joined to custdata rekeyed by account number.

### Config shape (`jsonEnricherConfig`)

| Field | Required | Meaning |
| ----- | -------- | ------- |
| `inputTopic` | Yes | JSON event stream. Validated against the API's input-topic allowlist. |
| `outputTopic` | Yes | Enriched envelope topic. Validated against the API's output-topic allowlist. |
| `source` | Yes | Literal copied into the output `source` field (for example, `csg`). |
| `joinKeyPath` | Yes | Dotted/indexed or JSON Pointer path resolved by `JsonPayloadPath` (for example, `AccountNum`, `account.number`, `items[0].account`, or `/account/number`). |
| `joinKeyCel` | Yes | CEL expression compiled when the definition is validated and when the topology is built. The only declared variable is string `key`. |
| `lookupTopic` | Yes | GlobalKTable source topic. It is validated against the **input-topic** allowlist. |
| `enrichmentName` | Yes | Dynamic property name in the single enrichment object (for example, `custdata`). |
| `streamProperties` | No | Kafka Streams properties. `bootstrap.servers` defaults to `localhost:9092`; set it explicitly for the runner environment. |

The API-managed `JobDefinition.jsonEnricherConfig` is the configuration source of truth. Its Java contract is `JsonEnricherConfig`; the live schema is available from `GET /jobs/schema`, and `POST /jobs/validate` applies the same semantic validation as create/update. The web console Basic editor exposes every field above. The runner forces `application.id` to `streammux-{jobId}`. It uses job `parallelism` as `num.stream.threads` unless `streamProperties` explicitly supplies that setting.

v1 supports exactly **one** lookup and one enrichment slot. The envelope uses an array so a future contract can add slots without changing the top-level shape.

### Lookup semantics

- The lookup topic is consumed with `String` keys and byte-array values. The normalized CEL result must exactly equal the Kafka record key; there is no numeric coercion, trimming, prefix match, or fallback lookup.
- A GlobalKTable materializes **all lookup-topic partitions on the active runner**, so input and lookup topics do not need matching partition counts or co-partitioning. Each lookup key's current table value is used; a Kafka tombstone removes that key.
- The input record's Kafka key is preserved on the output record. It is not replaced by the normalized join key.
- A lookup miss is a left-join result, not an error: the input is emitted with an empty array under `enrichmentName`.
- A null, empty, or non-JSON lookup value is treated like a miss. Any valid JSON lookup value (object, array, or scalar) is appended as the one array element.

### CEL join-key example

Hyphenated billing accounts `AAAA-BBBBBB-C` → concatenate 4 + 6 + last segment padded to 2 digits (`7707-938199-1` → `770793819901`):

```cel
size(key.split("-")) == 3
  ? key.split("-")[0] + key.split("-")[1]
    + (size(key.split("-")[2]) >= 2 ? key.split("-")[2] : "0" + key.split("-")[2])
  : key
```

Set `joinKeyCel` to `key` for an identity transform.

The extracted JSON field is passed to CEL as text. Scalar nodes use their text value; object or array nodes use their JSON representation. A non-null CEL result is converted to text before lookup; evaluation errors and null or blank results produce no join key.

### Output envelope

```json
{
  "source": "csg",
  "content": [
    {
      "AccountNum": "7707-938199-1",
      "JobNumber": "WO-1"
    }
  ],
  "enrichment": [
    {
      "custdata": [
        {
          "cmtsNm": "example-cmts"
        }
      ]
    }
  ]
}
```

`content` always contains the original parsed JSON value as its single element. `enrichment` always contains one object whose property name is `enrichmentName`; its value is either a one-element array for a hit or `[]` for a miss.

The record is dropped (not emitted) when the input value is null/empty or invalid JSON, the configured path is absent/null, the extracted value is blank, CEL evaluation fails or returns null/blank, or the output envelope cannot be serialized. These conditions are only debug-logged by reason and byte count where applicable; keys and payloads are not logged.

### Kafka and capacity prerequisites

Before activating a job:

- Provision read access to both `inputTopic` and `lookupTopic`, write access to `outputTopic`, and the Kafka Streams consumer-group/internal-topic permissions required by application id `streammux-{jobId}`.
- Configure `lookupTopic` as a keyed changelog suitable for a table: stable normalized string keys, valid JSON values, tombstones for deletion, and `cleanup.policy=compact` (optionally `compact,delete` only when its retention policy cannot discard still-current rows).
- Size partitions for producer/consumer throughput. GlobalKTable removes the co-partitioning requirement, but every active job instance restores every lookup partition.
- Budget local state, network, and restore time for a full copy of the lookup topic on the lease owner. A new host, cleared state directory, failover, or cold restart can replay the entire lookup history before the table is warm. Treat lookup-topic size and compaction backlog as deployment-capacity inputs.
- Set output retention and access controls for the combined dataset. The output inherits the highest classification of the input and lookup data.

**Privacy:** if either source contains Restricted subscriber/customer data, the enriched output is also Restricted. Do not place real records in job definitions, documentation, logs, screenshots, or test fixtures. Restrict output-topic readers to consumers authorized for both datasets.

### Sample CSG job

```bash
./create-json-enricher-job.sh
```

The sample creates `json-enricher-csg-osp-1` using:

- input `com.optimum.events.it.csg.osp.json`
- lookup `net.optimum.fixed.monitoring.network.access.custdata.acctnum.json`
- output `net.optimum.experimental.streamlens.streammux.csg-osp.enriched.json`
- join path `AccountNum` and the hyphen-normalization CEL expression above

The script contains configuration only, not customer records. Adapt topic names and `bootstrap.servers` to the target environment and validate allowlists/ACLs before running it. See [usage.md](usage.md#first-json_enricher-job-verification) for the canary and first-record verification sequence.

---

## Adding a new job type

Contributors extend the `JobRunner` SPI in `job-contracts`, add a runner module under `runners/`, and wire it into `site-orchestrator`'s Maven dependencies (Spring discovers the `@Component` automatically). Validation lives in `JobDefinitionValidator` inside `job-contracts`.

- Overview and conventions: [developer-guidelines.md](developer-guidelines.md)
- Full step-by-step checklist: [AGENT-MODULE-HOWTO.md](../AGENT-MODULE-HOWTO.md)

Both are developer references — not shown in the in-app Documentation page.
