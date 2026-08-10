package io.github.guillebot.streammux.api.controller;

import io.github.guillebot.streammux.api.service.ActivityService;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Activity", description = "Global audit feed and session identity")
@RestController
@RequestMapping("/activity")
public class ActivityController {
    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @Operation(summary = "List recent activity", description = "Returns recent audit events across all jobs, newest first.")
    @GetMapping
    public List<JobEvent> listActivity(
        @Parameter(description = "Maximum events to return (default 100, max 1000)") @RequestParam(name = "limit", defaultValue = "100") int limit,
        @Parameter(description = "Filter by job id (case-insensitive substring match)") @RequestParam(name = "jobId", required = false) String jobId,
        @Parameter(description = "Filter by event type") @RequestParam(name = "eventType", required = false) EventType eventType,
        @Parameter(description = "Filter by actor (Authelia user or API principal; case-insensitive substring match)") @RequestParam(name = "actor", required = false) String actor
    ) {
        return activityService.listActivity(limit, jobId, eventType, actor);
    }

    @Operation(summary = "Current actor", description = "Returns the resolved actor for the current request (Authelia user when proxied).")
    @GetMapping("/me")
    public Map<String, String> currentActor() {
        return Map.of("actor", activityService.currentActor());
    }

    @Operation(summary = "Record console session", description = "Records a platform SESSION event when the web UI loads.")
    @PostMapping("/session")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobEvent recordSession() {
        return activityService.recordSession();
    }
}
