package io.github.guillebot.streammux.api.controller;

import io.github.guillebot.streammux.api.service.ActivityService;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.EventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivityController.class)
@AutoConfigureMockMvc(addFilters = false)
class ActivityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActivityService activityService;

    @Test
    void listActivityReturnsEvents() throws Exception {
        JobEvent event = new JobEvent(
            "evt-1",
            "job-1",
            2,
            EventType.PAUSED,
            Instant.parse("2024-01-01T00:00:10Z"),
            null,
            "job-management-api",
            "Paused",
            Map.of("desiredState", "PAUSED"),
            "jsolarin"
        );
        when(activityService.listActivity(100, null, null, null)).thenReturn(List.of(event));

        mockMvc.perform(get("/activity"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].jobId").value("job-1"))
            .andExpect(jsonPath("$[0].eventType").value("PAUSED"))
            .andExpect(jsonPath("$[0].actor").value("jsolarin"));
    }

    @Test
    void listActivityPassesQueryFilters() throws Exception {
        when(activityService.listActivity(25, "job-1", List.of(EventType.STARTED), "operator")).thenReturn(List.of());

        mockMvc.perform(get("/activity")
                .param("limit", "25")
                .param("jobId", "job-1")
                .param("eventType", "STARTED")
                .param("actor", "operator"))
            .andExpect(status().isOk());

        verify(activityService).listActivity(25, "job-1", List.of(EventType.STARTED), "operator");
    }

    @Test
    void listActivityAcceptsMultipleEventTypes() throws Exception {
        when(activityService.listActivity(100, null, List.of(EventType.PAUSED, EventType.STARTED), null)).thenReturn(List.of());

        mockMvc.perform(get("/activity")
                .param("eventType", "PAUSED")
                .param("eventType", "STARTED"))
            .andExpect(status().isOk());

        verify(activityService).listActivity(100, null, List.of(EventType.PAUSED, EventType.STARTED), null);
    }

    @Test
    void currentActorReturnsResolvedIdentity() throws Exception {
        when(activityService.currentActor()).thenReturn("jsolarin");

        mockMvc.perform(get("/activity/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.actor").value("jsolarin"));
    }

    @Test
    void recordSessionReturnsAccepted() throws Exception {
        JobEvent session = new JobEvent(
            "sess-1",
            JobEvent.PLATFORM_JOB_ID,
            0,
            EventType.SESSION,
            Instant.parse("2024-01-01T00:00:00Z"),
            null,
            "job-management-api",
            "Console session",
            Map.of(),
            "jsolarin"
        );
        when(activityService.recordSession()).thenReturn(session);

        mockMvc.perform(post("/activity/session"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.eventType").value("SESSION"))
            .andExpect(jsonPath("$.actor").value("jsolarin"));
    }
}
