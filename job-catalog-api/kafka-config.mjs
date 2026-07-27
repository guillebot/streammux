/**
 * KafkaJS client config from Streammux env (matches roles/kstreams/streammux env.j2).
 */
export function buildKafkaClientConfig({ clientId, brokers }) {
  const protocol = (process.env.KAFKA_SECURITY_PROTOCOL || "PLAINTEXT").trim();
  const config = { clientId, brokers };

  if (protocol === "PLAINTEXT") {
    return config;
  }

  const rejectUnauthorized = (process.env.KAFKA_SSL_REJECT_UNAUTHORIZED || "false").toLowerCase() === "true";
  config.ssl = { rejectUnauthorized };

  const caLocation = (process.env.KAFKA_SSL_CA_LOCATION || "").trim();
  if (caLocation) {
    config.ssl.ca = [caLocation];
  }

  const endpointId = process.env.KAFKA_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM;
  if (endpointId === "") {
    config.ssl.rejectUnauthorized = false;
  }

  if (protocol.includes("SASL")) {
    config.sasl = {
      mechanism: (process.env.KAFKA_SASL_MECHANISM || "SCRAM-SHA-512").trim(),
      username: (process.env.KAFKA_SASL_USERNAME || "").trim(),
      password: process.env.KAFKA_SASL_PASSWORD || "",
    };
  }

  return config;
}
