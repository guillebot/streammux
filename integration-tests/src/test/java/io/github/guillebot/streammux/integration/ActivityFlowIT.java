package io.github.guillebot.streammux.integration;

import io.github.guillebot.streammux.api.config.KafkaTopicProperties;
import io.github.guillebot.streammux.api.service.ActivityService;
import io.github.guillebot.streammux.api.service.JobStateStore;
import io.github.guillebot.streammux.api.service.KafkaJobCommandPublisher;
import io.github.guillebot.streammux.api.service.KafkaJobStateProjector;
import io.github.guillebot.streammux.api.service.RequestActorResolver;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.EventType;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivityFlowIT extends KafkaIntegrationSupport {

    @Test
    void sessionEventsPublishToKafkaAndProjectIntoGlobalFeed() throws Exception {
        String prefix = "activity-it-" + UUID.randomUUID();
        KafkaTopicProperties topics = new KafkaTopicProperties(
            prefix + "-definitions",
            prefix + "-leases",
            prefix + "-status",
            prefix + "-events",
            prefix + "-commands"
        );
        createTopics(List.of(topics.jobEvents()));

        KafkaConsumer<String, byte[]> eventConsumer = createConsumer(topics.jobEvents());
        JobStateStore apiStateStore = new JobStateStore();
        JobStateStore projectedStateStore = new JobStateStore();
        RequestActorResolver actorResolver = mock(RequestActorResolver.class);
        when(actorResolver.currentActor()).thenReturn("jsolarin");

        ActivityService activityService = new ActivityService(
            apiStateStore,
            new KafkaJobCommandPublisher(kafkaTemplate(), topics),
            actorResolver
        );
        KafkaJobStateProjector projector = new KafkaJobStateProjector(projectedStateStore);

        JobEvent session = activityService.recordSession();
        projector.onJobEvent(pollSingleRecord(eventConsumer));

        assertEquals(EventType.SESSION, session.eventType());
        assertEquals("jsolarin", session.actor());
        assertEquals(JobEvent.PLATFORM_JOB_ID, session.jobId());
        assertEquals(1, projectedStateStore.listRecentEvents(10, null, EventType.SESSION, null).size());
        assertTrue(apiStateStore.listRecentEvents(10, null, null, "jsolarin").stream().anyMatch(e -> e.eventType() == EventType.SESSION));
    }

    private KafkaTemplate<String, Object> kafkaTemplate() {
        DefaultKafkaProducerFactory<String, Object> producerFactory = new DefaultKafkaProducerFactory<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
            JsonSerializer.ADD_TYPE_INFO_HEADERS, false
        ));
        return new KafkaTemplate<>(producerFactory);
    }
}
