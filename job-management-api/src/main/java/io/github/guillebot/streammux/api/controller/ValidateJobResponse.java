package io.github.guillebot.streammux.api.controller;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response body for POST /jobs/validate when the definition passes validation")
public record ValidateJobResponse(
    @Schema(description = "Always true; validation failures return HTTP 400 with a VALIDATION_ERROR envelope instead", example = "true")
    boolean valid
) {}
