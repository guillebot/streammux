package io.github.guillebot.streammux.contracts.model;

public final class TopicCleanupPolicy {
    public static final String COMPACT = "compact";
    public static final String DELETE = "delete";

    private TopicCleanupPolicy() {}

    public static String expectedForTopicKey(String topicKey) {
        if ("jobDefinitions".equals(topicKey) || "jobCatalog".equals(topicKey)) {
            return COMPACT;
        }
        return DELETE;
    }

    public static String normalize(String cleanupPolicy) {
        if (cleanupPolicy == null || cleanupPolicy.isBlank()) {
            return DELETE;
        }
        return cleanupPolicy.trim().toLowerCase();
    }

    public static boolean matches(String cleanupPolicy, String expected) {
        String actual = normalize(cleanupPolicy);
        if (COMPACT.equals(expected)) {
            return actual.contains(COMPACT);
        }
        if (DELETE.equals(expected)) {
            return !actual.contains(COMPACT);
        }
        return false;
    }

    public static boolean ok(boolean exists, String cleanupPolicy, String expected) {
        return exists && matches(cleanupPolicy, expected);
    }
}
