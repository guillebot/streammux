package io.github.guillebot.streammux.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.guillebot.streammux.api.service.JobDefinitionSchemaProvider;
import io.github.guillebot.streammux.api.service.JobService;
import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.CommandType;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LagMetrics;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.LeaseStatus;
import io.github.guillebot.streammux.contracts.model.PayloadFormat;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.WorkerMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JobController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JobDefinitionSchemaProvider.class)
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JobService jobService;

    @Test
    void createReturnsCreatedJob() throws Exception {
        JobDefinition definition = jobDefinition("job-1", 1, DesiredJobState.ACTIVE);
        when(jobService.createJob(definition)).thenReturn(definition);

        mockMvc.perform(post("/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(definition)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.jobId").value("job-1"))
            .andExpect(jsonPath("$.jobVersion").value(1));
    }

    @Test
    void createValidationErrorReturnsBadRequestWithMessage() throws Exception {
        JobDefinition definition = jobDefinition("job-1", 1, DesiredJobState.ACTIVE);
        when(jobService.createJob(definition)).thenThrow(new IllegalArgumentException("routeAppConfig.inputTopic is not allowed: input-topic"));

        mockMvc.perform(post("/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(definition)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("routeAppConfig.inputTopic is not allowed: input-topic"));
    }

    @Test
    void updateDelegatesToService() throws Exception {
        JobDefinition definition = jobDefinition("job-1", 2, DesiredJobState.PAUSED);
        when(jobService.updateJob("job-1", definition)).thenReturn(definition);

        mockMvc.perform(put("/jobs/job-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(definition)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.desiredState").value("PAUSED"));
    }

    @Test
    void commandEndpointsReturnAccepted() throws Exception {
        mockMvc.perform(post("/jobs/job-1/pause"))
            .andExpect(status().isAccepted());
        mockMvc.perform(post("/jobs/job-1/resume"))
            .andExpect(status().isAccepted());
        mockMvc.perform(post("/jobs/job-1/restart"))
            .andExpect(status().isAccepted());

        verify(jobService).issueCommand("job-1", CommandType.PAUSE);
        verify(jobService).issueCommand("job-1", CommandType.RESUME);
        verify(jobService).issueCommand("job-1", CommandType.RESTART);
    }

    @Test
    void validateReturnsValidTrueForGoodDefinition() throws Exception {
        JobDefinition definition = jobDefinition("job-1", 1, DesiredJobState.ACTIVE);

        mockMvc.perform(post("/jobs/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(definition)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true));

        verify(jobService).validate(definition);
    }

    @Test
    void validateBadPayloadReturnsBadRequestWithMessage() throws Exception {
        JobDefinition definition = jobDefinition("job-1", 1, DesiredJobState.ACTIVE);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("routeAppConfig.routes[0].filterExpression invalid: expected comparison operator after path 'foo' at position 4"))
            .when(jobService).validate(definition);

        mockMvc.perform(post("/jobs/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(definition)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("routeAppConfig.routes[0].filterExpression invalid: expected comparison operator after path 'foo' at position 4"));
    }

    @Test
    void getSchemaReturnsJsonSchemaDocument() throws Exception {
        mockMvc.perform(get("/jobs/schema"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/schema+json"))
            .andExpect(jsonPath("$.$schema").value("https://json-schema.org/draft/2020-12/schema"))
            .andExpect(jsonPath("$.$ref").value("#/$defs/JobDefinition"))
            .andExpect(jsonPath("$.$defs.JobDefinition").exists())
            .andExpect(jsonPath("$.$defs.RouteAppConfig").exists());
    }

    @Test
    void renameReturnsRenamedDefinition() throws Exception {
        JobDefinition renamed = jobDefinition("job-new", 1, DesiredJobState.ACTIVE);
        when(jobService.renameJob("job-old", "job-new")).thenReturn(renamed);

        mockMvc.perform(post("/jobs/job-old/rename")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newJobId\":\"job-new\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value("job-new"))
            .andExpect(jsonPath("$.jobVersion").value(1));

        verify(jobService).renameJob("job-old", "job-new");
    }

    @Test
    void renameBadRequestSurfacesAsFourHundred() throws Exception {
        when(jobService.renameJob(anyString(), anyString()))
            .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "newJobId is required"));

        mockMvc.perform(post("/jobs/job-old/rename")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newJobId\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void renameNotFoundSurfacesAsFourOhFour() throws Exception {
        when(jobService.renameJob(anyString(), anyString()))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: job-missing"));

        mockMvc.perform(post("/jobs/job-missing/rename")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newJobId\":\"job-new\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void renameConflictSurfacesAsFourOhNine() throws Exception {
        when(jobService.renameJob(anyString(), anyString()))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Job already exists: job-taken"));

        mockMvc.perform(post("/jobs/job-old/rename")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newJobId\":\"job-taken\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void deleteReturnsAccepted() throws Exception {
        mockMvc.perform(delete("/jobs/job-1"))
            .andExpect(status().isAccepted());

        verify(jobService).deleteJob("job-1");
    }

    @Test
    void nestedReadEndpointsReturnPayloads() throws Exception {
        when(jobService.getLease("job-1")).thenReturn(Optional.of(new JobLease(
            "job-1",
            3,
            "site-a",
            "instance-a",
            4,
            LeaseStatus.RUNNING,
            Instant.parse("2024-01-01T00:01:00Z"),
            Instant.parse("2024-01-01T00:00:30Z")
        )));
        when(jobService.getStatus("job-1")).thenReturn(Optional.of(new JobRuntimeStatus(
            "job-1",
            3,
            RuntimeState.RUNNING,
            HealthState.HEALTHY,
            Instant.parse("2024-01-01T00:00:30Z"),
            new WorkerMetadata("worker-1", "route-app", "RUNNING", Map.of()),
            null,
            new LagMetrics(0, 0, 1)
        )));
        when(jobService.getEvents("job-1")).thenReturn(List.of(
            new JobEvent("evt-1", "job-1", 3, EventType.STARTED, Instant.parse("2024-01-01T00:00:20Z"), "site-a", "instance-a", "started", Map.of(), "orchestrator")
        ));
        when(jobService.listJobs()).thenReturn(List.of(jobDefinition("job-1", 3, DesiredJobState.ACTIVE)));

        mockMvc.perform(get("/jobs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].jobId").value("job-1"));
        mockMvc.perform(get("/jobs/job-1/lease"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.leaseOwnerSite").value("site-a"));
        mockMvc.perform(get("/jobs/job-1/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("RUNNING"));
        mockMvc.perform(get("/jobs/job-1/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].eventType").value("STARTED"));
    }

    private static JobDefinition jobDefinition(String jobId, long version, DesiredJobState desiredState) {
        return new JobDefinition(
            jobId,
            version,
            JobType.ROUTE_APP,
            desiredState,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            new RouteAppConfig(
                "input-topic",
                PayloadFormat.JSON,
                PayloadFormat.JSON,
                null,
                List.of(new RouteDefinition("route-1", "message.type == \"ALARM\"", "alerts")),
                Map.of(),
                Map.of()
            ),
            null,
            null,
            Map.of("team", "mux"),
            List.of("test"),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }
}
