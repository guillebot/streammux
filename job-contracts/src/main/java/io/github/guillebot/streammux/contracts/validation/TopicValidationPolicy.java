package io.github.guillebot.streammux.contracts.validation;

import java.util.List;
import java.util.Objects;

public record TopicValidationPolicy(
    List<String> allowedInputTopics,
    List<String> allowedInputTopicPrefixes,
    List<String> allowedOutputTopics,
    List<String> allowedOutputTopicPrefixes
) {
    public TopicValidationPolicy {
        allowedInputTopics = sanitize(allowedInputTopics);
        allowedInputTopicPrefixes = sanitize(allowedInputTopicPrefixes);
        allowedOutputTopics = sanitize(allowedOutputTopics);
        allowedOutputTopicPrefixes = sanitize(allowedOutputTopicPrefixes);
    }

    public static TopicValidationPolicy unrestricted() {
        return new TopicValidationPolicy(List.of(), List.of(), List.of(), List.of());
    }

    public boolean isInputTopicAllowed(String topic) {
        return isAllowed(topic, allowedInputTopics, allowedInputTopicPrefixes);
    }

    public boolean isOutputTopicAllowed(String topic) {
        return isAllowed(topic, allowedOutputTopics, allowedOutputTopicPrefixes);
    }

    private static boolean isAllowed(String topic, List<String> exactTopics, List<String> prefixes) {
        if (topic == null || topic.isBlank()) {
            return false;
        }

        if (exactTopics.isEmpty() && prefixes.isEmpty()) {
            return true;
        }

        if (exactTopics.contains(topic)) {
            return true;
        }

        return prefixes.stream().anyMatch(topic::startsWith);
    }

    private static List<String> sanitize(List<String> values) {
        if (values == null) {
            return List.of();
        }

        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .toList();
    }
}
