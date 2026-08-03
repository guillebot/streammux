package io.github.guillebot.streammux.api.config;

import io.github.guillebot.streammux.api.controller.JobController;
import io.github.guillebot.streammux.api.service.JobService;
import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.PayloadFormat;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real Spring Security filter chain (unlike controller slice tests
 * that set {@code addFilters = false}).
 */
@WebMvcTest(controllers = JobController.class)
@Import(SecurityConfiguration.class)
class SecurityFilterChainTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JobService jobService;

    @Test
    void actuatorHealthIsPermitAll() throws Exception {
        // WebMvcTest does not wire Actuator endpoints, so the handler may 404 —
        // security must still not challenge anonymous callers on these paths.
        mockMvc.perform(get("/actuator/health"))
            .andExpect(result -> {
                int code = result.getResponse().getStatus();
                if (code == 401 || code == 403) {
                    throw new AssertionError("actuator/health should be permitAll, got HTTP " + code);
                }
            });
    }

    @Test
    void actuatorInfoIsPermitAll() throws Exception {
        mockMvc.perform(get("/actuator/info"))
            .andExpect(result -> {
                int code = result.getResponse().getStatus();
                if (code == 401 || code == 403) {
                    throw new AssertionError("actuator/info should be permitAll, got HTTP " + code);
                }
            });
    }

    @Test
    void jobsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/jobs").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void jobsRejectsBadCredentials() throws Exception {
        mockMvc.perform(get("/jobs")
                .with(httpBasic("streammux", "wrong-password"))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void jobsAllowsValidBasicAuth() throws Exception {
        when(jobService.listJobs()).thenReturn(List.of(sampleJob()));

        mockMvc.perform(get("/jobs")
                .with(httpBasic("streammux", "change-me-now"))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
    }

    private static JobDefinition sampleJob() {
        return new JobDefinition(
            "job-1",
            1,
            JobType.ROUTE_APP,
            DesiredJobState.ACTIVE,
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
