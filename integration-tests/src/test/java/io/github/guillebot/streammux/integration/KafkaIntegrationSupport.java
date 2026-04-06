package io.github.guillebot.streammux.integration;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

@Testcontainers
abstract class KafkaIntegrationSupport {
    @Container
    protected static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    private final List<KafkaConsumer<String, byte[]>> consumers = new ArrayList<>();

    @AfterEach
    void closeConsumers() {
        consumers.forEach(KafkaConsumer::close);
        consumers.clear();
    }

    protected String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    protected KafkaConsumer<String, byte[]> createConsumer(String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, topic + "-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(List.of(topic));
        consumers.add(consumer);
        return consumer;
    }

    protected void createTopics(Collection<String> topics) throws Exception {
        try (AdminClient adminClient = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers()))) {
            adminClient.createTopics(topics.stream().map(topic -> new NewTopic(topic, 1, (short) 1)).toList()).all().get();
        }
    }

    protected ConsumerRecord<String, byte[]> pollSingleRecord(KafkaConsumer<String, byte[]> consumer) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(250));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("Timed out waiting for Kafka record");
    }
}
