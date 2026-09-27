# Create an enrichment job

This is the operator guide for `JSON_ENRICHER`: left-join a JSON event stream to a lookup topic (GlobalKTable) using a CEL-normalized join key, and write a wrapped envelope. Contract details live in [job-types.md](job-types.md#json_enricher).

Use **MCP** when you want a ready-to-validate definition: `get_enricher_template`, `build_enricher_job`, then `validate_job`. Do not paste customer payloads into chat, tickets, or logs.

## Privacy (Restricted)

If the input **or** the lookup topic contains Restricted subscriber/customer data, the **output topic is Restricted**. Classify it before create. Restrict readers to principals authorized for both datasets. Hit-rate checks use **counts only** — never print keys or payloads.

## 1. Pick topics and the join field

| Piece | What to choose |
| ----- | -------------- |
| **Input** | JSON event topic (prod input allowlist: `com.optimum.`, `net.optimum.`, `gcp.optimum.`) |
| **Lookup** | Compacted, **string-keyed** JSON changelog. Also validated as an **input** topic. Keys must already be the normalized form you will compute (for custdata-by-account: **exactly 12 digits**). |
| **Output** | Under `net.optimum.experimental.streamlens.streammux.` (and the output allowlist `net.optimum.`). Example: `net.optimum.experimental.streamlens.streammux.<name>.enriched.json`. |
| **Join field** | `joinKeyPath` — dotted path or JSON Pointer (`AccountNum`, `account.number`, `/account/number`). |
| **Source / slot** | `source` is copied into the envelope (for example `csg`). `enrichmentName` is the property under `enrichment[0]` (for example `custdata`). |

Lookup keys are exact string matches. No trim, numeric coercion, or prefix match.

## 2. Choose a CEL recipe

`joinKeyCel` is compiled with one string variable: `key` (the extracted field as text). Blank/null CEL results **drop** the record (no output). A lookup **miss** still emits `enrichmentName: []`.

### Presets (MCP `cel_preset`)

| Preset | Use |
| ------ | --- |
| `acctnum-12` | CSG-style account numbers: three known input shapes → 12-digit lookup key |
| `identity` | Lookup keys already match the field (`key`) |
| `digits-only` | Strip non-digits, then take the first 12 (or all digits if fewer). Does **not** pad a one-digit check segment — use `acctnum-12` for hyphenated accounts. |

### Three AccountNum shapes (`acctnum-12`)

Custdata lookup keys are 12 digits. Event `AccountNum` has been observed in three shapes (synthetic examples only):

| Shape | Pattern | Normalization |
| ----- | ------- | ------------- |
| 13-char hyphenated | `NNNN-NNNNNN-N` | `parts[0] + parts[1] + last` padded to 2 digits |
| 14-char hyphenated | `NNNN-NNNNNN-NN` | `parts[0] + parts[1] + last` (already 2), truncated to 2 if longer |
| 15-char mixed | 12 digits + 3 letters | strip non-digits, take 12 (equivalently first 12 characters when digits lead) |

**Recommended CEL** (`acctnum-12`; hyphenated pad/truncate, otherwise strip non-digits and take 12):

```cel
size(key.split("-")) == 3
  ? key.split("-")[0] + key.split("-")[1]
    + (size(key.split("-")[2]) > 2
        ? key.split("-")[2].substring(0, 2)
        : (size(key.split("-")[2]) == 2
            ? key.split("-")[2]
            : "0" + key.split("-")[2]))
  : (size(regex.replace(key, "[^0-9]", "")) >= 12
      ? regex.replace(key, "[^0-9]", "").substring(0, 12)
      : regex.replace(key, "[^0-9]", ""))
```

`identity`:

```cel
key
```

`digits-only`:

```cel
size(regex.replace(key, "[^0-9]", "")) >= 12
  ? regex.replace(key, "[^0-9]", "").substring(0, 12)
  : regex.replace(key, "[^0-9]", "")
```

Preview synthetic strings with MCP `normalize_key_preview` (no Kafka). Do not pass real account numbers.

### What production job `json-enricher-csg-osp-1` actually runs

Read from the prod job-management-api on **2026-09-27** (`get_job`, `jobVersion` 1, `updatedAt` `2026-09-26T01:07:14.150050473Z`):

```cel
size(key.split("-")) == 3 ? key.split("-")[0] + key.split("-")[1] + (size(key.split("-")[2]) >= 2 ? key.split("-")[2] : "0" + key.split("-")[2]) : key
```

That expression covers **hyphenated** 13- and 14-character forms (pad last segment to 2 when shorter; does **not** truncate if last segment is longer than 2). The **else** branch is identity: a 15-character `12 digits + 3 letters` value is **not** reduced to 12 digits, so it will not match 12-digit lookup keys (missing/blank keys drop; this shape looks like a key but misses). Re-read `get_job` before changing docs or CEL; do not assume this snapshot is still current.

Topics on that job: input `com.optimum.events.it.csg.osp.json`, lookup `net.optimum.fixed.monitoring.network.access.custdata.acctnum.json`, output `net.optimum.experimental.streamlens.streammux.csg-osp.enriched.json`. Envelope: `{source:"csg", content:[orig], enrichment:[{custdata:[hit|[]]}]}`.

## 3. Validate, then create

1. `build_enricher_job` (or copy the sample in `create-json-enricher-job.sh`) with prod `streamProperties.bootstrap.servers` (Rednet `kb101`–`kb105` `:19092` for production).
2. `validate_job` / `POST /jobs/validate`. Input and lookup must pass the **input** allowlist; output the **output** allowlist; CEL must compile.
3. Create **PAUSED**, confirm ACLs (`streammux-{jobId}` group + Streams internals), then set `ACTIVE`. Prefer one orchestrator host while bringing up a new enricher (see [deployment notes](deployment.md#json_enricher-rollout)).
4. Confirm `GET /jobs/{id}/lease` has an owner and `GET /jobs/{id}/status` is **RUNNING**. GlobalKTable restore can take a long time on a cold host. Keep `STREAMMUX_LEASE_DURATION_FLOOR_SECONDS` (default 600) so a 30s job lease TTL cannot expire mid-restore.

MCP: `get_job_status` / `get_job_lease`. Web console Basic tab also edits `jsonEnricherConfig`.

## 4. Verify hits with counts only

There are no dedicated hit/miss counters. Do **not** dump output records.

On a consumer authorized for Restricted data, count envelopes only:

- **Hit:** `enrichment[0].<enrichmentName>` is a one-element array.
- **Miss:** that array is `[]` (record still emitted).
- **Drop:** no output (bad JSON, missing path, blank/failed CEL).

Compare input rate vs output rate from `GET /jobs/{id}/status` (`inputCount` / `outputCount`). Output ≈ input minus drops; output is **not** the hit count.

Synthetic fixtures (non-sensitive keys/values) are the only payloads that belong in tickets. See [usage.md](usage.md#first-json_enricher-job-verification).

## 5. Troubleshooting (0% hits)

| Symptom | Likely cause |
| ------- | ------------ |
| Output count ≈ 0 while input moves | Join key dropped (path missing, CEL blank/error) or runner not RUNNING |
| Output count high, hit-rate ~0% | **Key shape mismatch** — event CEL result ≠ lookup Kafka key (hyphens, check digit, letters). Preview with `normalize_key_preview` on **synthetic** samples. Confirm lookup keys are 12 digits. |
| Hits after a long gap, or 0% then sudden hits | **GlobalKTable restore** still replaying the compacted changelog |
| Runner flaps, repeated CLAIM/STOP | Lease storm / first-start replay — see [deployment.md](deployment.md#json_enricher-operational-notes); one orchestrator until the pinned image includes the lease fixes |
| `validate_job` OK, host never starts runner | Image/tag missing on host — kstreams nodes **cannot pull** `registry.gitlab.com`; sideload the release tarball |

## MCP checklist

1. `get_doc(docs/enricher-guide.md)`
2. `get_enricher_template` — presets and CEL
3. `normalize_key_preview` — synthetic strings only
4. `build_enricher_job` — JobDefinition JSON (does not persist)
5. `validate_job` → `create_job` with `apply=true` only after review
6. `get_job_status` / `get_job_lease` until RUNNING
7. Counts-only hit check; never print Restricted payloads
