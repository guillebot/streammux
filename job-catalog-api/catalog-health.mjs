export const TOPIC_CLEANUP = {
  COMPACT: "compact",
  DELETE: "delete",
};

export function expectedCleanupPolicy(topicKey) {
  if (
    topicKey === "jobDefinitions" ||
    topicKey === "jobLeases" ||
    topicKey === "jobStatus" ||
    topicKey === "jobCatalog"
  ) {
    return TOPIC_CLEANUP.COMPACT;
  }
  return TOPIC_CLEANUP.DELETE;
}

export function normalizeCleanupPolicy(cleanupPolicy) {
  if (!cleanupPolicy || !String(cleanupPolicy).trim()) return TOPIC_CLEANUP.DELETE;
  return String(cleanupPolicy).trim().toLowerCase();
}

export function cleanupPolicyMatches(cleanupPolicy, expected) {
  const actual = normalizeCleanupPolicy(cleanupPolicy);
  if (expected === TOPIC_CLEANUP.COMPACT) return actual.includes(TOPIC_CLEANUP.COMPACT);
  if (expected === TOPIC_CLEANUP.DELETE) return !actual.includes(TOPIC_CLEANUP.COMPACT);
  return false;
}

export function topicHealthEntry(topicKey, topicName, exists, cleanupPolicy) {
  const expected = expectedCleanupPolicy(topicKey);
  const ok = exists && cleanupPolicyMatches(cleanupPolicy, expected);
  return { key: topicKey, name: topicName, exists, cleanupPolicy: cleanupPolicy ?? null, expected, ok };
}
