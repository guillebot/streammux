package io.github.guillebot.streammux.api.controller;

import io.github.guillebot.streammux.api.service.KafkaTopicCatalogService;
import io.github.guillebot.streammux.api.service.KafkaTopicCatalogService.KafkaTopicCatalog;
import io.github.guillebot.streammux.api.service.PlatformHealthService;
import io.github.guillebot.streammux.api.service.PlatformHealthService.KafkaHealth;
import io.github.guillebot.streammux.api.service.PlatformHealthService.ModuleHealth;
import io.github.guillebot.streammux.api.service.PlatformHealthService.PlatformHealth;
import io.github.guillebot.streammux.api.service.PlatformHealthService.ReadModelHealth;
import io.github.guillebot.streammux.api.service.PlatformHealthService.TopicPresence;
import io.github.guillebot.streammux.api.service.PlatformSettingsService;
import io.github.guillebot.streammux.api.service.PlatformSettingsService.ApiSettings;
import io.github.guillebot.streammux.api.service.PlatformSettingsService.KafkaSettings;
import io.github.guillebot.streammux.api.service.PlatformSettingsService.ModuleSettings;
import io.github.guillebot.streammux.api.service.PlatformSettingsService.PlatformSettings;
import io.github.guillebot.streammux.api.service.PlatformSettingsService.TopicSettings;
import io.github.guillebot.streammux.api.service.PlatformSettingsService.TopicValidationSettings;
import io.github.guillebot.streammux.contracts.model.TopicNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JobMetaController.class)
@AutoConfigureMockMvc(addFilters = false)
class JobMetaControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KafkaTopicCatalogService kafkaTopicCatalogService;

    @MockBean
    private PlatformHealthService platformHealthService;

    @MockBean
    private PlatformSettingsService platformSettingsService;

    @Test
    void returnsBrokerFilteredTopicLists() throws Exception {
        when(kafkaTopicCatalogService.listAllowedTopics()).thenReturn(new KafkaTopicCatalog(
            "kafka1:9092,kafka2:9092",
            List.of("lab.optimum.events.in", "lab.optimum.telemetry.in"),
            List.of("net.optimum.experimental.streamlens.streammux.alerts")
        ));

        mockMvc.perform(get("/jobs/meta/kafka-topics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bootstrapServers").value("kafka1:9092,kafka2:9092"))
            .andExpect(jsonPath("$.inputTopics.length()").value(2))
            .andExpect(jsonPath("$.inputTopics[0]").value("lab.optimum.events.in"))
            .andExpect(jsonPath("$.outputTopics.length()").value(1))
            .andExpect(jsonPath("$.outputTopics[0]").value("net.optimum.experimental.streamlens.streammux.alerts"));
    }

    @Test
    void returnsPlatformHealthSnapshot() throws Exception {
        when(platformHealthService.snapshot()).thenReturn(new PlatformHealth(
            "UP",
            Instant.parse("2026-01-01T00:00:00Z"),
            new ModuleHealth("job-management-api", "UP"),
            new KafkaHealth(
                "UP",
                "kafka1:9092",
                "cluster-1",
                3,
                List.of(new TopicPresence("jobDefinitions", TopicNames.JOB_DEFINITIONS, true))
            ),
            new ReadModelHealth(2, 1, 1, 1)
        ));

        mockMvc.perform(get("/jobs/meta/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.module.name").value("job-management-api"))
            .andExpect(jsonPath("$.kafka.brokerCount").value(3))
            .andExpect(jsonPath("$.readModel.jobCount").value(2));
    }

    @Test
    void returnsPlatformSettingsSnapshot() throws Exception {
        when(platformSettingsService.snapshot()).thenReturn(new PlatformSettings(
            Instant.parse("2026-01-01T00:00:00Z"),
            new ModuleSettings("job-management-api"),
            new KafkaSettings("kafka1:9092", "job-management-api-read-model"),
            new TopicSettings(
                TopicNames.JOB_DEFINITIONS,
                TopicNames.JOB_LEASES,
                TopicNames.JOB_STATUS,
                TopicNames.JOB_EVENTS,
                TopicNames.JOB_COMMANDS
            ),
            new TopicValidationSettings(
                List.of("net.optimum.monitoring.in"),
                List.of("net.optimum.monitoring."),
                List.of(),
                List.of("net.optimum.experimental.streamlens.streammux.")
            ),
            new ApiSettings(List.of("health", "info"), true)
        ));

        mockMvc.perform(get("/jobs/meta/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.module.applicationName").value("job-management-api"))
            .andExpect(jsonPath("$.kafka.bootstrapServers").value("kafka1:9092"))
            .andExpect(jsonPath("$.topics.jobDefinitions").value(TopicNames.JOB_DEFINITIONS))
            .andExpect(jsonPath("$.validation.allowedInputTopics[0]").value("net.optimum.monitoring.in"))
            .andExpect(jsonPath("$.api.exposedActuatorEndpoints[0]").value("health"))
            .andExpect(jsonPath("$.api.springdocShowActuator").value(true));
    }
}
