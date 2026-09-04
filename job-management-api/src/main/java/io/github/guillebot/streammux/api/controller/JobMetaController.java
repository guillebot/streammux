package io.github.guillebot.streammux.api.controller;

import io.github.guillebot.streammux.api.service.KafkaTopicCatalogService;
import io.github.guillebot.streammux.api.service.KafkaTopicCatalogService.KafkaTopicCatalog;
import io.github.guillebot.streammux.api.service.PlatformHealthIssuesService;
import io.github.guillebot.streammux.api.service.PlatformHealthIssuesService.HealthIssuesSnapshot;
import io.github.guillebot.streammux.api.service.PlatformHealthIssuesService.HealthSummary;
import io.github.guillebot.streammux.api.service.PlatformHealthService;
import io.github.guillebot.streammux.api.service.PlatformHealthService.PlatformHealth;
import io.github.guillebot.streammux.api.service.PlatformSettingsService;
import io.github.guillebot.streammux.api.service.PlatformSettingsService.PlatformSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Job metadata", description = "Broker-backed metadata for job authoring")
@RestController
@RequestMapping("/jobs/meta")
public class JobMetaController {
    private final KafkaTopicCatalogService kafkaTopicCatalogService;
    private final PlatformHealthService platformHealthService;
    private final PlatformHealthIssuesService platformHealthIssuesService;
    private final PlatformSettingsService platformSettingsService;

    public JobMetaController(
        KafkaTopicCatalogService kafkaTopicCatalogService,
        PlatformHealthService platformHealthService,
        PlatformHealthIssuesService platformHealthIssuesService,
        PlatformSettingsService platformSettingsService
    ) {
        this.kafkaTopicCatalogService = kafkaTopicCatalogService;
        this.platformHealthService = platformHealthService;
        this.platformHealthIssuesService = platformHealthIssuesService;
        this.platformSettingsService = platformSettingsService;
    }

    @Operation(
        summary = "List allowed Kafka topics",
        description = "Returns broker topic names filtered by the configured input/output allowlists."
    )
    @GetMapping("/kafka-topics")
    public KafkaTopicCatalog kafkaTopics() {
        return kafkaTopicCatalogService.listAllowedTopics();
    }

    @Operation(
        summary = "Platform health snapshot",
        description = "Kafka connectivity, configured topic presence, and in-memory read-model counts."
    )
    @GetMapping("/health")
    public PlatformHealth health() {
        return platformHealthService.snapshot();
    }

    @Operation(summary = "Platform health issue summary", description = "Counts of active issues by severity for the header bell.")
    @GetMapping("/health/summary")
    public HealthSummary healthSummary() {
        return platformHealthIssuesService.summary();
    }

    @Operation(summary = "Platform health issues", description = "Structured issues for jobs, Kafka, and lag thresholds.")
    @GetMapping("/health/issues")
    public HealthIssuesSnapshot healthIssues() {
        return platformHealthIssuesService.issues();
    }

    @Operation(
        summary = "Platform settings snapshot",
        description = "Curated non-secret configuration: Kafka, topic names, validation allowlists, and API metadata."
    )
    @GetMapping("/settings")
    public PlatformSettings settings() {
        return platformSettingsService.snapshot();
    }
}
