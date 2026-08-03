import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import { buildKafkaClientConfig } from "./kafka-config.mjs";

const ENV_KEYS = [
  "KAFKA_SECURITY_PROTOCOL",
  "KAFKA_SSL_REJECT_UNAUTHORIZED",
  "KAFKA_SSL_CA_LOCATION",
  "KAFKA_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM",
  "KAFKA_SASL_MECHANISM",
  "KAFKA_SASL_USERNAME",
  "KAFKA_SASL_PASSWORD",
];

afterEach(() => {
  for (const key of ENV_KEYS) {
    delete process.env[key];
  }
});

test("PLAINTEXT returns brokers only", () => {
  process.env.KAFKA_SECURITY_PROTOCOL = "PLAINTEXT";
  const cfg = buildKafkaClientConfig({ clientId: "c1", brokers: ["b:9092"] });
  assert.deepEqual(cfg, { clientId: "c1", brokers: ["b:9092"] });
});

test("SASL_SSL includes ssl and sasl blocks", () => {
  process.env.KAFKA_SECURITY_PROTOCOL = "SASL_SSL";
  process.env.KAFKA_SSL_REJECT_UNAUTHORIZED = "true";
  process.env.KAFKA_SASL_USERNAME = "user";
  process.env.KAFKA_SASL_PASSWORD = "secret";
  const cfg = buildKafkaClientConfig({ clientId: "c1", brokers: ["b:9093"] });
  assert.equal(cfg.ssl.rejectUnauthorized, true);
  assert.equal(cfg.sasl.mechanism, "SCRAM-SHA-512");
  assert.equal(cfg.sasl.username, "user");
  assert.equal(cfg.sasl.password, "secret");
});

test("empty endpoint identification algorithm disables TLS verify", () => {
  process.env.KAFKA_SECURITY_PROTOCOL = "SSL";
  process.env.KAFKA_SSL_REJECT_UNAUTHORIZED = "true";
  process.env.KAFKA_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM = "";
  const cfg = buildKafkaClientConfig({ clientId: "c1", brokers: ["b:9093"] });
  assert.equal(cfg.ssl.rejectUnauthorized, false);
});
