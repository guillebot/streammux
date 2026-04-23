package io.github.guillebot.streammux.integration;

import io.github.guillebot.streammux.contracts.config.RandomSamplerConfig;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import io.github.guillebot.streammux.randomsampler.RandomSamplerRunner;
import io.github.guillebot.streammux.randomsampler.config.RandomSamplerTopologyFactory;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomSamplerRunnerIT extends KafkaIntegrationSupport {

    @Test
    void forwardsRecordsWhenRateIsOne() throws Exception {
        String prefix = "rs-it-" + UUID.randomUUID();
        String inputTopic = prefix + "-in";
        String outputTopic = prefix + "-out";
        createTopics(List.of(inputTopic, outputTopic));

        JobDefinition job = randomSamplerJob("job-rs-1", inputTopic, outputTopic, 1.0d);
        RandomSamplerRunner runner = new RandomSamplerRunner(new RandomSamplerTopologyFactory());
        runner.start(job, 1L);
        try {
            assertEquals(RuntimeState.RUNNING, runner.status(job.jobId()).state());
            assertEquals(HealthState.HEALTHY, runner.status(job.jobId()).health());
            byte[] payload = "{\"n\":1}".getBytes(StandardCharsets.UTF_8);
            try (KafkaProducer<String, byte[]> producer = byteArrayProducer()) {
                producer.send(new ProducerRecord<>(inputTopic, "k1", payload)).get();
            }
            KafkaConsumer<String, byte[]> out = createConsumer(outputTopic);
            warmAssign(out);
            assertEventuallyReceives(out, payload, Duration.ofSeconds(45));
        } finally {
            runner.stop(job.jobId());
        }
        assertEquals(RuntimeState.STOPPED, runner.status(job.jobId()).state());
    }

    @Test
    void dropsRecordsWhenRateIsZero() throws Exception {
        String prefix = "rs-it-" + UUID.randomUUID();
        String inputTopic = prefix + "-in";
        String outputTopic = prefix + "-out";
        createTopics(List.of(inputTopic, outputTopic));

        JobDefinition job = randomSamplerJob("job-rs-0", inputTopic, outputTopic, 0.0d);
        RandomSamplerRunner runner = new RandomSamplerRunner(new RandomSamplerTopologyFactory());
        runner.start(job, 1L);
        try {
            try (KafkaProducer<String, byte[]> producer = byteArrayProducer()) {
                producer.send(new ProducerRecord<>(inputTopic, "k1", "x".getBytes(StandardCharsets.UTF_8))).get();
            }
            KafkaConsumer<String, byte[]> out = createConsumer(outputTopic);
            warmAssign(out);
            assertTrue(noRecordsWithin(out, Duration.ofSeconds(8)));
        } finally {
            runner.stop(job.jobId());
        }
    }

    @Test
    void restartWithNewLeaseEpochContinuesForwarding() throws Exception {
        String prefix = "rs-it-" + UUID.randomUUID();
        String inputTopic = prefix + "-in";
        String outputTopic = prefix + "-out";
        createTopics(List.of(inputTopic, outputTopic));

        JobDefinition job = randomSamplerJob("job-rs-restart", inputTopic, outputTopic, 1.0d);
        RandomSamplerRunner runner = new RandomSamplerRunner(new RandomSamplerTopologyFactory());
        KafkaConsumer<String, byte[]> out = createConsumer(outputTopic);
        warmAssign(out);
        try {
            runner.start(job, 1L);
            byte[] firstPayload = "{\"n\":1}".getBytes(StandardCharsets.UTF_8);
            publish(inputTopic, "k1", firstPayload);
            assertEventuallyReceives(out, firstPayload, Duration.ofSeconds(45));

            runner.start(job, 2L);
            assertEquals(RuntimeState.RUNNING, runner.status(job.jobId()).state());
            byte[] secondPayload = "{\"n\":2}".getBytes(StandardCharsets.UTF_8);
            publish(inputTopic, "k2", secondPayload);
            assertEventuallyReceives(out, secondPayload, Duration.ofSeconds(45));
        } finally {
            runner.stop(job.jobId());
        }
    }

    private KafkaProducer<String, byte[]> byteArrayProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new KafkaProducer<>(props);
    }

    private void publish(String topic, String key, byte[] payload) throws Exception {
        try (KafkaProducer<String, byte[]> producer = byteArrayProducer()) {
            producer.send(new ProducerRecord<>(topic, key, payload)).get();
        }
    }

    private static void warmAssign(KafkaConsumer<String, byte[]> consumer) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(200));
            if (!consumer.assignment().isEmpty()) {
                return;
            }
        }
        throw new AssertionError("Consumer did not receive partition assignment in time");
    }

    private static boolean noRecordsWithin(KafkaConsumer<String, byte[]> consumer, Duration window) {
        Instant end = Instant.now().plus(window);
        while (Instant.now().isBefore(end)) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(400));
            if (!records.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void assertEventuallyReceives(KafkaConsumer<String, byte[]> consumer, byte[] expected, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                assertArrayEquals(expected, records.iterator().next().value());
                return;
            }
        }
        throw new AssertionError("Timed out waiting for output record");
    }

    private JobDefinition randomSamplerJob(String jobId, String inputTopic, String outputTopic, double rate) {
        return new JobDefinition(
            jobId,
            1,
            JobType.RANDOM_SAMPLER,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            null,
            new RandomSamplerConfig(
                inputTopic,
                outputTopic,
                rate,
                Map.of(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers())
            ),
            null,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "integration"
        );
    }
}
