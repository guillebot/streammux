package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.TopicValidationProperties;
import io.github.guillebot.streammux.contracts.validation.TopicValidationPolicy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.common.KafkaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@Service
public class KafkaTopicCatalogService {
    private final String bootstrapServers;
    private final TopicValidationProperties topicValidationProperties;

    public KafkaTopicCatalogService(
        @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
        TopicValidationProperties topicValidationProperties
    ) {
        this.bootstrapServers = bootstrapServers;
        this.topicValidationProperties = topicValidationProperties;
    }

    public KafkaTopicCatalog listAllowedTopics() {
        TopicValidationPolicy policy = topicValidationProperties.toPolicy();
        Set<String> brokerTopics = listBrokerTopicNames();
        return new KafkaTopicCatalog(
            bootstrapServers,
            filterTopics(brokerTopics, policy::isInputTopicAllowed),
            filterTopics(brokerTopics, policy::isOutputTopicAllowed)
        );
    }

    static List<String> filterTopics(Collection<String> topics, java.util.function.Predicate<String> allowed) {
        List<String> filtered = new ArrayList<>();
        for (String topic : topics) {
            if (topic == null || topic.isBlank() || isInternalTopic(topic)) {
                continue;
            }
            if (allowed.test(topic)) {
                filtered.add(topic);
            }
        }
        filtered.sort(Comparator.naturalOrder());
        return List.copyOf(filtered);
    }

    static boolean isInternalTopic(String topic) {
        return topic.startsWith("_");
    }

    private Set<String> listBrokerTopicNames() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        try (AdminClient admin = AdminClient.create(props)) {
            ListTopicsOptions options = new ListTopicsOptions().listInternal(false);
            return admin.listTopics(options).names().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaException("Interrupted while listing Kafka topics", e);
        } catch (ExecutionException e) {
            throw new KafkaException("Failed to list Kafka topics", e.getCause());
        }
    }

    public record KafkaTopicCatalog(String bootstrapServers, List<String> inputTopics, List<String> outputTopics) {}
}
