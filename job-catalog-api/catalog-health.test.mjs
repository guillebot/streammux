import assert from "node:assert/strict";
import { test } from "node:test";
import {
  TOPIC_CLEANUP,
  cleanupPolicyMatches,
  expectedCleanupPolicy,
  normalizeCleanupPolicy,
  topicHealthEntry,
} from "./catalog-health.mjs";

test("compact topics expect compact cleanup policy", () => {
  for (const key of ["jobDefinitions", "jobLeases", "jobStatus", "jobCatalog"]) {
    assert.equal(expectedCleanupPolicy(key), TOPIC_CLEANUP.COMPACT);
  }
  assert.equal(expectedCleanupPolicy("jobEvents"), TOPIC_CLEANUP.DELETE);
});

test("normalizeCleanupPolicy defaults blank to delete", () => {
  assert.equal(normalizeCleanupPolicy(null), "delete");
  assert.equal(normalizeCleanupPolicy("  "), "delete");
  assert.equal(normalizeCleanupPolicy("Compact"), "compact");
});

test("cleanupPolicyMatches accepts compact+delete combo for compact expected", () => {
  assert.equal(cleanupPolicyMatches("compact", TOPIC_CLEANUP.COMPACT), true);
  assert.equal(cleanupPolicyMatches("compact,delete", TOPIC_CLEANUP.COMPACT), true);
  assert.equal(cleanupPolicyMatches("delete", TOPIC_CLEANUP.COMPACT), false);
  assert.equal(cleanupPolicyMatches("delete", TOPIC_CLEANUP.DELETE), true);
  assert.equal(cleanupPolicyMatches("compact", TOPIC_CLEANUP.DELETE), false);
});

test("topicHealthEntry marks missing or wrong policy as not ok", () => {
  assert.deepEqual(topicHealthEntry("jobCatalog", "catalog", false, null), {
    key: "jobCatalog",
    name: "catalog",
    exists: false,
    cleanupPolicy: null,
    expected: "compact",
    ok: false,
  });
  assert.equal(topicHealthEntry("jobCatalog", "catalog", true, "compact").ok, true);
  assert.equal(topicHealthEntry("jobCatalog", "catalog", true, "delete").ok, false);
});
