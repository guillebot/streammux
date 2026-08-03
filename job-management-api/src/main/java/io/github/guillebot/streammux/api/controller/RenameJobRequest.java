package io.github.guillebot.streammux.api.controller;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request body for POST /jobs/{jobId}/rename")
public record RenameJobRequest(
    @Schema(description = "New job id under which the definition should be published", example = "route-poc-1-renamed")
    String newJobId
) {}
