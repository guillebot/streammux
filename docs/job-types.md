# Job types

Streammux supports pluggable **job runners**. Each `JobDefinition` includes a `jobType` and a type-specific config block. The site orchestrator resolves the matching runner and starts it when the orchestrator holds the job lease.

| Job type | Config field | Runner module |
| -------- | ------------ | ------------- |
| `ROUTE_APP` | `routeAppConfig` | `runners/job-runner-route-app` |
| `RANDOM_SAMPLER` | `randomSamplerConfig` | `runners/job-runner-random-sampler` |
| `ALARMS_TO_ZTR` | `alarmsToZtrConfig` | `runners/job-runner-alarms-to-ztr` |

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

## Adding a new job type

Contributors extend the `JobRunner` SPI in `job-contracts`, add a runner module under `runners/`, and wire it into `site-orchestrator`'s Maven dependencies (Spring discovers the `@Component` automatically). Validation lives in `JobDefinitionValidator` inside `job-contracts`.

- Overview and conventions: [developer-guidelines.md](developer-guidelines.md)
- Full step-by-step checklist: [AGENT-MODULE-HOWTO.md](../AGENT-MODULE-HOWTO.md)

Both are developer references — not shown in the in-app Documentation page.
