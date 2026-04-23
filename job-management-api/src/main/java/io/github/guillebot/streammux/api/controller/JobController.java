package io.github.guillebot.streammux.api.controller;

import io.github.guillebot.streammux.api.service.JobService;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.CommandType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Tag(name = "Jobs", description = "Job definitions, lifecycle commands, and Kafka-projected status, lease, and event views")
@RestController
@RequestMapping("/jobs")
public class JobController {
    private final JobService jobService;

    public JobController(JobService jobService) { this.jobService = jobService; }

    @Operation(summary = "Create job", description = "Registers a new job and publishes the definition to Kafka.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Job created"),
        @ApiResponse(responseCode = "409", description = "Job id already exists")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobDefinition create(@RequestBody JobDefinition definition) { return jobService.createJob(definition); }

    @Operation(summary = "List jobs")
    @GetMapping
    public Collection<JobDefinition> list() { return jobService.listJobs(); }

    @Operation(summary = "Get job")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Job definition"),
        @ApiResponse(responseCode = "404", description = "Job not found")
    })
    @GetMapping("/{jobId}")
    public JobDefinition get(@Parameter(description = "Job identifier") @PathVariable("jobId") String jobId) { return jobService.getJob(jobId); }

    @Operation(summary = "Update job", description = "Increments version, validates payload, and publishes the updated definition.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Job updated"),
        @ApiResponse(responseCode = "404", description = "Job not found")
    })
    @PutMapping("/{jobId}")
    public JobDefinition update(
        @Parameter(description = "Job identifier") @PathVariable("jobId") String jobId,
        @RequestBody JobDefinition definition
    ) { return jobService.updateJob(jobId, definition); }

    @Operation(summary = "Pause job")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Pause command accepted"),
        @ApiResponse(responseCode = "404", description = "Job not found")
    })
    @PostMapping("/{jobId}/pause")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void pause(@Parameter(description = "Job identifier") @PathVariable("jobId") String jobId) { jobService.issueCommand(jobId, CommandType.PAUSE); }

    @Operation(summary = "Resume job")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Resume command accepted"),
        @ApiResponse(responseCode = "404", description = "Job not found")
    })
    @PostMapping("/{jobId}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resume(@Parameter(description = "Job identifier") @PathVariable("jobId") String jobId) { jobService.issueCommand(jobId, CommandType.RESUME); }

    @Operation(summary = "Restart job")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Restart command accepted"),
        @ApiResponse(responseCode = "404", description = "Job not found")
    })
    @PostMapping("/{jobId}/restart")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void restart(@Parameter(description = "Job identifier") @PathVariable("jobId") String jobId) { jobService.issueCommand(jobId, CommandType.RESTART); }

    @Operation(summary = "Delete job", description = "Marks the job deleted, publishes commands/events, and removes it from the local read model.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Delete accepted"),
        @ApiResponse(responseCode = "404", description = "Job not found")
    })
    @DeleteMapping("/{jobId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void delete(@Parameter(description = "Job identifier") @PathVariable("jobId") String jobId) { jobService.deleteJob(jobId); }

    @Operation(
        summary = "Runtime status",
        description = "Returns JSON when status exists for this job; empty body with HTTP 200 when the read model has no status yet."
    )
    @GetMapping("/{jobId}/status")
    public Optional<JobRuntimeStatus> status(@Parameter(description = "Job identifier") @PathVariable("jobId") String jobId) { return jobService.getStatus(jobId); }

    @Operation(
        summary = "Lease",
        description = "Returns JSON when a lease exists for this job; empty body with HTTP 200 when none is projected yet."
    )
    @GetMapping("/{jobId}/lease")
    public Optional<JobLease> lease(@Parameter(description = "Job identifier") @PathVariable("jobId") String jobId) { return jobService.getLease(jobId); }

    @Operation(summary = "Job events", description = "Audit-style events for the job from the read model (may be empty).")
    @GetMapping("/{jobId}/events")
    public List<JobEvent> events(@Parameter(description = "Job identifier") @PathVariable("jobId") String jobId) { return jobService.getEvents(jobId); }
}
