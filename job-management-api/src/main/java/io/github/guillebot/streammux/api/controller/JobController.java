package io.github.guillebot.streammux.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.guillebot.streammux.api.service.JobDefinitionSchemaProvider;
import io.github.guillebot.streammux.api.service.JobService;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.CommandType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private static final String SCHEMA_MEDIA_TYPE = "application/schema+json";

    private final JobService jobService;
    private final JobDefinitionSchemaProvider schemaProvider;
    private final ObjectMapper objectMapper;

    public JobController(JobService jobService, JobDefinitionSchemaProvider schemaProvider, ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.schemaProvider = schemaProvider;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "Create job", description = "Registers a new job and publishes the definition to Kafka.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Job created"),
        @ApiResponse(responseCode = "409", description = "Job id already exists")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobDefinition create(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(schema = @Schema(implementation = JobDefinition.class))
        )
        @RequestBody JsonNode payload
    ) {
        return jobService.createJob(bindDefinition(payload));
    }

    @Operation(summary = "List jobs")
    @GetMapping
    public Collection<JobDefinition> list() { return jobService.listJobs(); }

    @Operation(
        summary = "Job definition JSON Schema",
        description = "Returns the JSON Schema 2020-12 document that describes JobDefinition and every referenced type. "
            + "The same schema is applied server-side before create/update/validate bind the payload, so the editor and the API agree on shape."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "JSON Schema document")
    })
    @GetMapping(path = "/schema", produces = SCHEMA_MEDIA_TYPE)
    public ResponseEntity<JsonNode> schema() {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(SCHEMA_MEDIA_TYPE))
            .body(schemaProvider.getSchemaJson());
    }

    @Operation(
        summary = "Validate job",
        description = "Runs the same validator used by create/update/rename without persisting or publishing anything. Returns 200 with {\"valid\":true} when the definition passes and 400 VALIDATION_ERROR (matching a real save) when it does not."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Definition is valid"),
        @ApiResponse(responseCode = "400", description = "Definition failed validation; body has the VALIDATION_ERROR envelope")
    })
    @PostMapping("/validate")
    public ValidateJobResponse validate(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(schema = @Schema(implementation = JobDefinition.class))
        )
        @RequestBody JsonNode payload
    ) {
        jobService.validate(bindDefinition(payload));
        return new ValidateJobResponse(true);
    }

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
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(schema = @Schema(implementation = JobDefinition.class))
        )
        @RequestBody JsonNode payload
    ) { return jobService.updateJob(jobId, bindDefinition(payload)); }

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

    @Operation(
        summary = "Rename job",
        description = "Copies the current definition under the new jobId key, tombstones the old key with desiredState=DELETED, and emits paired CREATED/DELETED events with rename attributes. All other fields (jobType, desiredState, config, labels, tags, etc.) are copied from the existing definition; use PUT /jobs/{jobId} first if you need to change them at the same time. Runtime state (status, lease) and version history do not carry over; any running runner restarts under the new id."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Job renamed; returns the new definition"),
        @ApiResponse(responseCode = "400", description = "Invalid or missing newJobId (blank, or equal to path jobId)"),
        @ApiResponse(responseCode = "404", description = "Job not found"),
        @ApiResponse(responseCode = "409", description = "newJobId already exists")
    })
    @PostMapping("/{jobId}/rename")
    public JobDefinition rename(
        @Parameter(description = "Current job identifier") @PathVariable("jobId") String jobId,
        @RequestBody RenameJobRequest request
    ) { return jobService.renameJob(jobId, request.newJobId()); }

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

    /**
     * Runs schema validation on the raw payload, then binds it into a {@link JobDefinition}
     * record. Schema failures (wrong types, bad enums, unknown fields) and binding failures both
     * bubble up as {@link IllegalArgumentException}, which the shared exception handler maps to
     * a {@code 400 VALIDATION_ERROR}. Semantic checks run afterwards in the service layer.
     */
    private JobDefinition bindDefinition(JsonNode payload) {
        schemaProvider.validate(payload);
        try {
            return objectMapper.treeToValue(payload, JobDefinition.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to parse JobDefinition: " + ex.getOriginalMessage(), ex);
        }
    }
}
