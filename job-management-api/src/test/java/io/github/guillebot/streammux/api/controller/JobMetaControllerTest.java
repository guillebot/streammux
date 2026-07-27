package io.github.guillebot.streammux.api.controller;

import io.github.guillebot.streammux.api.service.KafkaTopicCatalogService;
import io.github.guillebot.streammux.api.service.KafkaTopicCatalogService.KafkaTopicCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

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

    @Test
    void returnsBrokerFilteredTopicLists() throws Exception {
        when(kafkaTopicCatalogService.listAllowedTopics()).thenReturn(new KafkaTopicCatalog(
            "kafka1:9092,kafka2:9092",
            List.of("lab.optimum.events.in", "lab.optimum.telemetry.in"),
            List.of("lab.optimum.experimental.streamlens.streammux.alerts")
        ));

        mockMvc.perform(get("/jobs/meta/kafka-topics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bootstrapServers").value("kafka1:9092,kafka2:9092"))
            .andExpect(jsonPath("$.inputTopics.length()").value(2))
            .andExpect(jsonPath("$.inputTopics[0]").value("lab.optimum.events.in"))
            .andExpect(jsonPath("$.outputTopics.length()").value(1))
            .andExpect(jsonPath("$.outputTopics[0]").value("lab.optimum.experimental.streamlens.streammux.alerts"));
    }
}
