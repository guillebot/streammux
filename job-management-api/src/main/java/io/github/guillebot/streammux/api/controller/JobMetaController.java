package io.github.guillebot.streammux.api.controller;

import io.github.guillebot.streammux.api.service.KafkaTopicCatalogService;
import io.github.guillebot.streammux.api.service.KafkaTopicCatalogService.KafkaTopicCatalog;
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

    public JobMetaController(KafkaTopicCatalogService kafkaTopicCatalogService) {
        this.kafkaTopicCatalogService = kafkaTopicCatalogService;
    }

    @Operation(
        summary = "List allowed Kafka topics",
        description = "Returns broker topic names filtered by the configured input/output allowlists."
    )
    @GetMapping("/kafka-topics")
    public KafkaTopicCatalog kafkaTopics() {
        return kafkaTopicCatalogService.listAllowedTopics();
    }
}
