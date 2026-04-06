package io.github.guillebot.streammux.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JobController.class)
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
            new JobEvent("evt-1", "job-1", 3, EventType.STARTED, Instant.parse("2024-01-01T00:00:20Z"), "site-a", "instance-a", "started", Map.of())
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
            Map.of("team", "mux"),
            List.of("test"),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }
}
