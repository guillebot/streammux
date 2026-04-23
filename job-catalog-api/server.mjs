import express from "express";
import { Kafka } from "kafkajs";
import { randomUUID } from "node:crypto";
import { hostname } from "node:os";

const PORT = Number(process.env.PORT ?? 3000);
const JOB_API_RAW = (process.env.JOB_MANAGEMENT_API_URL ?? "").trim().replace(/\/$/, "");
const BROKERS = (process.env.KAFKA_BOOTSTRAP_SERVERS ?? "")
  .split(",")
  .map((s) => s.trim())
  .filter(Boolean);
const TOPIC = (process.env.STREAMMUX_TOPIC_JOB_CATALOG ?? "").trim();
const CLIENT_ID = process.env.KAFKA_CLIENT_ID ?? `job-catalog-api-${hostname()}`;
const CREATE_TOPIC = (process.env.KAFKA_JOB_CATALOG_CREATE_TOPIC ?? "true").toLowerCase() !== "false";
const PARTITIONS = Math.max(1, Number(process.env.STREAMMUX_TOPIC_JOB_CATALOG_PARTITIONS ?? 1));
const REPLICATION = Math.max(1, Number(process.env.KAFKA_REPLICATION_FACTOR ?? 1));

if (!BROKERS.length) {
  console.error("job-catalog-api: KAFKA_BOOTSTRAP_SERVERS is required (comma-separated brokers)");
  process.exit(1);
}
if (!JOB_API_RAW) {
  console.error("job-catalog-api: JOB_MANAGEMENT_API_URL is required (e.g. http://job-management-api:8080)");
  process.exit(1);
}
if (!TOPIC) {
  console.error("job-catalog-api: STREAMMUX_TOPIC_JOB_CATALOG is required");
  process.exit(1);
}

const JOB_API = JOB_API_RAW;

const kafka = new Kafka({ clientId: CLIENT_ID, brokers: BROKERS });
const producer = kafka.producer({
  allowAutoTopicCreation: false,
  idempotent: true,
  maxInFlightRequests: 1,
});
const admin = kafka.admin();

/** @type {Map<number, { id: number, title: string, payload: object, createdAt: string, updatedAt: string }>} */
const entries = new Map();
/** @type {Map<number, bigint>} */
const maxSeenOffset = new Map();

let nextId = 1;

/** Serialize creates/updates/deletes so numeric ids stay unique under concurrent HTTP handlers. */
let writeChain = Promise.resolve();
function withCatalogWrite(fn) {
  const p = writeChain.then(fn, fn);
  writeChain = p.catch(() => {});
  return p;
}

function keyBuf(id) {
  return Buffer.from(String(id), "utf8");
}

function nowIso() {
  return new Date().toISOString();
}

function parsePayloadJobId(payloadObj) {
  return typeof payloadObj?.jobId === "string" ? payloadObj.jobId : null;
}

function syncNextIdFromMap() {
  let m = 0;
  for (const id of entries.keys()) {
    if (id > m) m = id;
  }
  if (nextId <= m) nextId = m + 1;
}

function applyCatalogMessage(keyStr, valueBuf) {
  const id = Number(keyStr);
  if (!Number.isInteger(id) || id < 1) {
    console.error(`Ignoring catalog message with invalid key: ${keyStr}`);
    return;
  }
  if (valueBuf == null) {
    entries.delete(id);
    return;
  }
  let row;
  try {
    row = JSON.parse(valueBuf.toString("utf8"));
  } catch {
    console.error(`Ignoring non-JSON catalog value for key ${keyStr}`);
    return;
  }
  if (typeof row.id !== "number" || row.id !== id) {
    console.error(`Catalog value id mismatch key=${keyStr} body.id=${row?.id}`);
    return;
  }
  if (typeof row.title !== "string" || typeof row.createdAt !== "string" || typeof row.updatedAt !== "string") {
    console.error(`Catalog value missing fields for key ${keyStr}`);
    return;
  }
  if (typeof row.payload !== "object" || row.payload === null) {
    console.error(`Catalog value payload must be object for key ${keyStr}`);
    return;
  }
  entries.set(id, {
    id: row.id,
    title: row.title,
    payload: row.payload,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
  });
  if (id >= nextId) nextId = id + 1;
}

async function ensureTopic() {
  if (!CREATE_TOPIC) return;
  const ok = await admin.createTopics({
    waitForLeaders: true,
    topics: [
      {
        topic: TOPIC,
        numPartitions: PARTITIONS,
        replicationFactor: REPLICATION,
        configEntries: [
          { name: "cleanup.policy", value: "compact" },
          { name: "compression.type", value: "producer" },
          { name: "min.cleanable.dirty.ratio", value: "0.01" },
          { name: "delete.retention.ms", value: "86400000" },
        ],
      },
    ],
  });
  if (!ok) {
    console.error(`Topic ${TOPIC} already exists (or createTopics returned false); using existing config`);
  }
}

/**
 * Wait until offsets observed from the log reach the high-water marks from `snap`
 * (taken just before the consumer started). New records appended during replay are
 * still delivered afterwards by the running consumer.
 */
async function waitUntilSnapCaughtUp(snap) {
  const targets = new Map();
  for (const p of snap) {
    const hi = BigInt(p.high);
    const lo = BigInt(p.low);
    if (hi <= lo) targets.set(p.partition, null);
    else targets.set(p.partition, hi - 1n);
  }
  for (;;) {
    await new Promise((r) => setTimeout(r, 40));
    let ok = true;
    for (const [part, need] of targets) {
      if (need === null) continue;
      const got = maxSeenOffset.get(part) ?? -1n;
      if (got < need) {
        ok = false;
        break;
      }
    }
    if (ok) return;
  }
}

async function sendCatalogRecord(record) {
  const value = JSON.stringify({
    id: record.id,
    title: record.title,
    payload: record.payload,
    createdAt: record.createdAt,
    updatedAt: record.updatedAt,
  });
  await producer.send({
    topic: TOPIC,
    messages: [{ key: keyBuf(record.id), value }],
  });
}

async function sendTombstone(id) {
  await producer.send({
    topic: TOPIC,
    messages: [{ key: keyBuf(id), value: null }],
  });
}

async function readApiError(response) {
  const text = await response.text();
  if (!text) return response.statusText || "request failed";
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed.message === "string" && parsed.message) return parsed.message;
    if (parsed && typeof parsed.error === "string" && parsed.error) return parsed.error;
  } catch {
    // plain text body; fall through
  }
  return text.length > 240 ? `${text.slice(0, 240)}...` : text;
}

/** @type {import("express").RequestHandler} */
function notFound(_req, res) {
  res.status(404).json({ error: "Not found" });
}

const router = express.Router();

router.get("/entries", (_req, res) => {
  const list = [...entries.values()]
    .map((r) => ({
      id: r.id,
      title: r.title,
      jobId: parsePayloadJobId(r.payload),
      createdAt: r.createdAt,
      updatedAt: r.updatedAt,
    }))
    .sort((a, b) => (a.updatedAt < b.updatedAt ? 1 : a.updatedAt > b.updatedAt ? -1 : 0));
  res.json(list);
});

router.get("/entries/:id", (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id)) return notFound(req, res);
  const row = entries.get(id);
  if (!row) return notFound(req, res);
  res.json({
    id: row.id,
    title: row.title,
    payload: row.payload,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
  });
});

router.post("/entries", async (req, res) => {
  const title = typeof req.body?.title === "string" ? req.body.title : "";
  const payload = req.body?.payload;
  if (payload === undefined || typeof payload !== "object" || payload === null) {
    return res.status(400).json({ error: "Body must include JSON object `payload` (job definition)" });
  }
  try {
    JSON.stringify(payload);
  } catch {
    return res.status(400).json({ error: "payload is not serializable" });
  }
  const t = nowIso();
  try {
    const record = await withCatalogWrite(async () => {
      const id = nextId;
      const rec = { id, title, payload, createdAt: t, updatedAt: t };
      await sendCatalogRecord(rec);
      nextId = id + 1;
      entries.set(id, rec);
      return rec;
    });
    res.status(201).json(record);
  } catch (e) {
    res.status(500).json({ error: e instanceof Error ? e.message : String(e) });
  }
});

router.put("/entries/:id", async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id)) return notFound(req, res);
  const titleIn = typeof req.body?.title === "string" ? req.body.title : undefined;
  const payload = req.body?.payload;
  if (payload !== undefined && (typeof payload !== "object" || payload === null)) {
    return res.status(400).json({ error: "`payload` must be a JSON object when provided" });
  }
  if (payload !== undefined) {
    try {
      JSON.stringify(payload);
    } catch {
      return res.status(400).json({ error: "payload is not serializable" });
    }
  }
  try {
    const updated = await withCatalogWrite(async () => {
      const existing = entries.get(id);
      if (!existing) {
        const err = new Error("NOT_FOUND");
        /** @type {any} */ (err).code = "NOT_FOUND";
        throw err;
      }
      const nextTitle = titleIn !== undefined ? titleIn : existing.title;
      const nextPayload = payload !== undefined ? payload : existing.payload;
      const t = nowIso();
      const rec = {
        id,
        title: nextTitle,
        payload: nextPayload,
        createdAt: existing.createdAt,
        updatedAt: t,
      };
      await sendCatalogRecord(rec);
      entries.set(id, rec);
      return rec;
    });
    res.json(updated);
  } catch (e) {
    if (/** @type {any} */ (e).code === "NOT_FOUND") return notFound(req, res);
    res.status(500).json({ error: e instanceof Error ? e.message : String(e) });
  }
});

router.delete("/entries/:id", async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id)) return notFound(req, res);
  try {
    await withCatalogWrite(async () => {
      if (!entries.has(id)) {
        const err = new Error("NOT_FOUND");
        /** @type {any} */ (err).code = "NOT_FOUND";
        throw err;
      }
      await sendTombstone(id);
      entries.delete(id);
    });
    res.status(204).end();
  } catch (e) {
    if (/** @type {any} */ (e).code === "NOT_FOUND") return notFound(req, res);
    res.status(500).json({ error: e instanceof Error ? e.message : String(e) });
  }
});

router.post("/entries/:id/duplicate", async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id)) return notFound(req, res);
  const t = nowIso();
  try {
    const record = await withCatalogWrite(async () => {
      const src = entries.get(id);
      if (!src) {
        const err = new Error("NOT_FOUND");
        /** @type {any} */ (err).code = "NOT_FOUND";
        throw err;
      }
      const baseTitle = src.title?.trim() ? src.title : "Entry";
      const newTitle = `Copy of ${baseTitle}`;
      const newId = nextId;
      const payload = structuredClone(src.payload);
      const rec = { id: newId, title: newTitle, payload, createdAt: t, updatedAt: t };
      await sendCatalogRecord(rec);
      nextId = newId + 1;
      entries.set(newId, rec);
      return rec;
    });
    res.status(201).json(record);
  } catch (e) {
    if (/** @type {any} */ (e).code === "NOT_FOUND") return notFound(req, res);
    res.status(500).json({ error: e instanceof Error ? e.message : String(e) });
  }
});

router.post("/entries/:id/push", async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id)) return notFound(req, res);
  const row = entries.get(id);
  if (!row) return notFound(req, res);
  const job = row.payload;
  if (typeof job?.jobId !== "string" || !job.jobId) {
    return res.status(400).json({ error: "payload.jobId must be a non-empty string" });
  }
  const jobId = job.jobId;
  const url = `${JOB_API}/jobs/${encodeURIComponent(jobId)}`;
  try {
    let probe = await fetch(url, { method: "GET" });
    let apiRes;
    if (probe.status === 404) {
      apiRes = await fetch(`${JOB_API}/jobs`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(job),
      });
    } else if (probe.ok) {
      apiRes = await fetch(url, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(job),
      });
    } else {
      const detail = await readApiError(probe);
      return res.status(502).json({ error: `job-management-api probe failed: ${probe.status} ${detail}` });
    }
    if (!apiRes.ok) {
      const detail = await readApiError(apiRes);
      const isClientInputIssue = apiRes.status >= 400 && apiRes.status < 500;
      return res
        .status(isClientInputIssue ? 400 : 502)
        .json({ error: `Push rejected by job-management-api: ${detail}` });
    }
    const text = await apiRes.text();
    const body = text ? JSON.parse(text) : null;
    res.json({ job: body });
  } catch (e) {
    res.status(502).json({ error: e instanceof Error ? e.message : String(e) });
  }
});

const app = express();
app.use(express.json({ limit: "4mb" }));
app.use("/catalog", router);

app.get("/health", (_req, res) => {
  res.json({ ok: true, backend: "kafka", topic: TOPIC });
});

async function main() {
  await admin.connect();
  await ensureTopic();

  await producer.connect();

  const groupId = `streammux-job-catalog-${randomUUID()}`;
  const consumer = kafka.consumer({
    groupId,
    sessionTimeout: 60000,
    heartbeatInterval: 3000,
  });
  await consumer.connect();
  await consumer.subscribe({ topic: TOPIC, fromBeginning: true });

  const running = consumer.run({
    eachMessage: async ({ partition, message }) => {
      const o = BigInt(message.offset);
      const keyStr = message.key ? message.key.toString("utf8") : "";
      applyCatalogMessage(keyStr, message.value);
      const prev = maxSeenOffset.get(partition) ?? -1n;
      if (o > prev) maxSeenOffset.set(partition, o);
    },
  });

  running.catch((e) => {
    console.error("Kafka consumer stopped with error", e);
    process.exit(1);
  });

  /** High water may move while replaying; settle when two reads match after a catch-up pass. */
  let prevHwKey = null;
  for (let i = 0; i < 40; i++) {
    const snap = await admin.fetchTopicOffsets(TOPIC);
    const hwKey = snap
      .map((x) => `${x.partition}:${x.high}:${x.low}`)
      .sort()
      .join("|");
    await waitUntilSnapCaughtUp(snap);
    await new Promise((r) => setTimeout(r, 50));
    if (prevHwKey === hwKey) break;
    prevHwKey = hwKey;
  }
  await new Promise((r) => setTimeout(r, 100));
  syncNextIdFromMap();

  app.listen(PORT, () => {
    console.log(
      `[job-catalog-api] listening on port ${PORT}; topic=${TOPIC}; brokers=${BROKERS.join(",")}; jobManagementApi=${JOB_API}; createTopic=${CREATE_TOPIC}`,
    );
  });
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
