package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.KafkaTopicProperties;
import io.github.guillebot.streammux.contracts.model.TopicCleanupPolicy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.DescribeConfigsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.config.ConfigResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@Service
public class PlatformHealthService {
    private static final String CLEANUP_POLICY = "cleanup.policy";
    private static final Duration KAFKA_PROBE_CACHE_TTL = Duration.ofSeconds(60);

    private final String bootstrapServers;
    private final KafkaTopicProperties topicProperties;
    private final JobStateStore stateStore;
    private volatile CachedKafkaProbe kafkaProbeCache;

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
        String status = worstStatus(module.status(), kafka.status());
        return new PlatformHealth(status, checkedAt, module, kafka, readModel);
    }

    private KafkaHealth probeKafka() {
        Instant now = Instant.now();
        CachedKafkaProbe cached = kafkaProbeCache;
        if (cached != null && cached.validUntil.isAfter(now)) {
            return cached.kafka;
        }

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        try (AdminClient admin = AdminClient.create(props)) {
            DescribeClusterResult cluster = admin.describeCluster();
            String clusterId = cluster.clusterId().get();
            int brokerCount = cluster.nodes().get().size();
            List<String> controlPlaneTopics = controlPlaneTopicNames();
            Set<String> brokerTopics = describeControlPlaneTopics(admin, controlPlaneTopics);
            Map<String, String> cleanupPolicies = fetchCleanupPolicies(admin, brokerTopics);
            List<TopicPresence> topics = buildTopicPresence(brokerTopics, cleanupPolicies);
            boolean allOk = topics.stream().allMatch(TopicPresence::ok);
            String status = brokerCount > 0 && allOk ? "UP" : "DEGRADED";
            if (brokerCount == 0) {
                status = "DOWN";
            }
            KafkaHealth kafka = new KafkaHealth(status, bootstrapServers, clusterId, brokerCount, topics);
            kafkaProbeCache = new CachedKafkaProbe(kafka, now.plus(KAFKA_PROBE_CACHE_TTL));
            return kafka;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return kafkaDown("Interrupted while probing Kafka");
        } catch (ExecutionException e) {
            return kafkaDown(rootCauseMessage(e));
        } catch (KafkaException e) {
            return kafkaDown(e.getMessage());
        }
    }

    private List<String> controlPlaneTopicNames() {
        List<String> names = new ArrayList<>(5);
        addTopicName(names, topicProperties.jobDefinitions());
        addTopicName(names, topicProperties.jobLeases());
        addTopicName(names, topicProperties.jobStatus());
        addTopicName(names, topicProperties.jobEvents());
        addTopicName(names, topicProperties.jobCommands());
        return List.copyOf(names);
    }

    private static void addTopicName(List<String> names, String topicName) {
        if (topicName != null && !topicName.isBlank() && !names.contains(topicName)) {
            names.add(topicName);
        }
    }

    private static Set<String> describeControlPlaneTopics(AdminClient admin, List<String> topicNames)
        throws ExecutionException, InterruptedException {
        if (topicNames.isEmpty()) {
            return Set.of();
        }
        DescribeTopicsResult result = admin.describeTopics(new HashSet<>(topicNames));
        Map<String, TopicDescription> described = result.allTopicNames().get();
        return Set.copyOf(described.keySet());
    }

    private Map<String, String> fetchCleanupPolicies(AdminClient admin, Set<String> topicNames)
        throws ExecutionException, InterruptedException {
        List<ConfigResource> resources = topicNames.stream()
            .map(name -> new ConfigResource(ConfigResource.Type.TOPIC, name))
            .toList();
        if (resources.isEmpty()) {
            return Map.of();
        }
        DescribeConfigsResult result = admin.describeConfigs(resources);
        Map<ConfigResource, Config> configs = result.all().get();
        Map<String, String> policies = new LinkedHashMap<>();
        for (Map.Entry<ConfigResource, Config> entry : configs.entrySet()) {
            ConfigEntry cleanup = entry.getValue().get(CLEANUP_POLICY);
            if (cleanup != null && cleanup.value() != null) {
                policies.put(entry.getKey().name(), cleanup.value());
            }
        }
        return policies;
    }

    private List<TopicPresence> buildTopicPresence(Set<String> brokerTopics, Map<String, String> cleanupPolicies) {
        List<TopicPresence> topics = new ArrayList<>();
        topics.add(topicPresence("jobDefinitions", topicProperties.jobDefinitions(), brokerTopics, cleanupPolicies));
        topics.add(topicPresence("jobLeases", topicProperties.jobLeases(), brokerTopics, cleanupPolicies));
        topics.add(topicPresence("jobStatus", topicProperties.jobStatus(), brokerTopics, cleanupPolicies));
        topics.add(topicPresence("jobEvents", topicProperties.jobEvents(), brokerTopics, cleanupPolicies));
        topics.add(topicPresence("jobCommands", topicProperties.jobCommands(), brokerTopics, cleanupPolicies));
        return List.copyOf(topics);
    }

    private static TopicPresence topicPresence(
        String key,
        String topicName,
        Set<String> brokerTopics,
        Map<String, String> cleanupPolicies
    ) {
        boolean exists = topicName != null && !topicName.isBlank() && brokerTopics.contains(topicName);
        String expected = TopicCleanupPolicy.expectedForTopicKey(key);
        String cleanupPolicy = exists ? cleanupPolicies.get(topicName) : null;
        boolean ok = TopicCleanupPolicy.ok(exists, cleanupPolicy, expected);
        return new TopicPresence(key, topicName, exists, cleanupPolicy, expected, ok);
    }

    // Aggregate sub-component statuses using the standard three-level ordering
    // (DOWN worst, then DEGRADED, then UP). Anything unrecognised is treated as
    // DEGRADED so we surface it without pretending the platform is healthy.
    static String worstStatus(String... statuses) {
        boolean sawDegraded = false;
        for (String s : statuses) {
            if ("DOWN".equals(s)) {
                return "DOWN";
            }
            if (!"UP".equals(s)) {
                sawDegraded = true;
            }
        }
        return sawDegraded ? "DEGRADED" : "UP";
    }

    private KafkaHealth kafkaDown(String detail) {
        List<TopicPresence> topics = buildTopicPresence(Set.of(), Map.of());
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

    public record TopicPresence(
        String key,
        String name,
        boolean exists,
        String cleanupPolicy,
        String expected,
        boolean ok
    ) {}

    public record ReadModelHealth(int jobCount, int leaseCount, int statusCount, int eventJobCount) {}

    private record CachedKafkaProbe(KafkaHealth kafka, Instant validUntil) {}
}
