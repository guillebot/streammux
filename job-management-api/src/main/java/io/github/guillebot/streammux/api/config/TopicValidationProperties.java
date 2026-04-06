package io.github.guillebot.streammux.api.config;

import io.github.guillebot.streammux.contracts.validation.TopicValidationPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "streammux.validation.topics")
public record TopicValidationProperties(
    List<String> allowedInputTopics,
    List<String> allowedInputTopicPrefixes,
    List<String> allowedOutputTopics,
    List<String> allowedOutputTopicPrefixes
) {
    public TopicValidationPolicy toPolicy() {
        return new TopicValidationPolicy(
            allowedInputTopics,
            allowedInputTopicPrefixes,
            allowedOutputTopics,
            allowedOutputTopicPrefixes
        );
    }
}
