package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.KafkaTopicProperties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.common.KafkaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@Service
public class PlatformHealthService {
    private final String bootstrapServers;
    private final KafkaTopicProperties topicProperties;
    private final JobStateStore stateStore;

    public PlatformHealthService(
        @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
        KafkaTopicProperties topicProperties,
        JobStateStore stateStore
    ) {
        this.bootstrapServers = bootstrapServers;
        this.topicProperties = topicProperties;
        this.stateStore = stateStore;
    }

    public PlatformHealth snapshot() {
        Instant checkedAt = Instant.now();
        KafkaHealth kafka = probeKafka();
        ReadModelHealth readModel = new ReadModelHealth(
            stateStore.listJobs().size(),
            stateStore.snapshotLeaseCount(),
            stateStore.snapshotStatusCount(),
            stateStore.snapshotEventJobCount()
        );
        ModuleHealth module = new ModuleHealth("job-management-api", "UP");
        String status = "UP".equals(kafka.status()) ? "UP" : "DOWN";
        return new PlatformHealth(status, checkedAt, module, kafka, readModel);
    }

    private KafkaHealth probeKafka() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        try (AdminClient admin = AdminClient.create(props)) {
            DescribeClusterResult cluster = admin.describeCluster();
            String clusterId = cluster.clusterId().get();
            int brokerCount = cluster.nodes().get().size();
            ListTopicsOptions options = new ListTopicsOptions().listInternal(false);
            Set<String> brokerTopics = admin.listTopics(options).names().get();
            List<TopicPresence> topics = buildTopicPresence(brokerTopics);
            boolean allPresent = topics.stream().allMatch(TopicPresence::present);
            String status = brokerCount > 0 && allPresent ? "UP" : "DEGRADED";
            if (brokerCount == 0) {
                status = "DOWN";
            }
            return new KafkaHealth(status, bootstrapServers, clusterId, brokerCount, topics);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return kafkaDown("Interrupted while probing Kafka");
        } catch (ExecutionException e) {
            return kafkaDown(rootCauseMessage(e));
        } catch (KafkaException e) {
            return kafkaDown(e.getMessage());
        }
    }

    private List<TopicPresence> buildTopicPresence(Set<String> brokerTopics) {
        List<TopicPresence> topics = new ArrayList<>();
        topics.add(topicPresence("jobDefinitions", topicProperties.jobDefinitions(), brokerTopics));
        topics.add(topicPresence("jobLeases", topicProperties.jobLeases(), brokerTopics));
        topics.add(topicPresence("jobStatus", topicProperties.jobStatus(), brokerTopics));
        topics.add(topicPresence("jobEvents", topicProperties.jobEvents(), brokerTopics));
        topics.add(topicPresence("jobCommands", topicProperties.jobCommands(), brokerTopics));
        return List.copyOf(topics);
    }

    private static TopicPresence topicPresence(String key, String topicName, Set<String> brokerTopics) {
        boolean present = topicName != null && !topicName.isBlank() && brokerTopics.contains(topicName);
        return new TopicPresence(key, topicName, present);
    }

    private KafkaHealth kafkaDown(String detail) {
        List<TopicPresence> topics = buildTopicPresence(Set.of());
        return new KafkaHealth("DOWN", bootstrapServers, null, 0, topics, detail);
    }

    private static String rootCauseMessage(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return e.getMessage();
    }

    public record PlatformHealth(
        String status,
        Instant checkedAt,
        ModuleHealth module,
        KafkaHealth kafka,
        ReadModelHealth readModel
    ) {}

    public record ModuleHealth(String name, String status) {}

    public record KafkaHealth(
        String status,
        String bootstrapServers,
        String clusterId,
        int brokerCount,
        List<TopicPresence> topics,
        String detail
    ) {
        public KafkaHealth(
            String status,
            String bootstrapServers,
            String clusterId,
            int brokerCount,
            List<TopicPresence> topics
        ) {
            this(status, bootstrapServers, clusterId, brokerCount, topics, null);
        }
    }

    public record TopicPresence(String key, String name, boolean present) {}

    public record ReadModelHealth(int jobCount, int leaseCount, int statusCount, int eventJobCount) {}
}
