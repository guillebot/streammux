package io.github.guillebot.streammux.contracts.spi;

import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;

public interface JobRunner {
    boolean supports(JobDefinition jobDefinition);
    void start(JobDefinition jobDefinition, long leaseEpoch);
    void stop(String jobId);
    JobRuntimeStatus status(String jobId);
}
