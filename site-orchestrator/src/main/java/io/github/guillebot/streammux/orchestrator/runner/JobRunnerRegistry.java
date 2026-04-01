package io.github.guillebot.streammux.orchestrator.runner;

import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.spi.JobRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JobRunnerRegistry {
    private final List<JobRunner> runners;

    public JobRunnerRegistry(List<JobRunner> runners) { this.runners = runners; }

    public JobRunner resolve(JobDefinition definition) {
        return runners.stream().filter(runner -> runner.supports(definition)).findFirst().orElseThrow(() -> new IllegalStateException("No runner for job type " + definition.jobType()));
    }
}
