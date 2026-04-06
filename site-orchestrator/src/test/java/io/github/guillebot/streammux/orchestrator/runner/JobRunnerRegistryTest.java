package io.github.guillebot.streammux.orchestrator.runner;

import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.spi.JobRunner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobRunnerRegistryTest {

    @Test
    void resolvesFirstSupportingRunner() {
        JobRunner first = mock(JobRunner.class);
        JobRunner second = mock(JobRunner.class);
        JobDefinition definition = jobDefinition();
        when(first.supports(definition)).thenReturn(false);
        when(second.supports(definition)).thenReturn(true);

        JobRunner resolved = new JobRunnerRegistry(List.of(first, second)).resolve(definition);

        assertEquals(second, resolved);
    }

    @Test
    void throwsWhenNoRunnerSupportsDefinition() {
        JobRunner runner = mock(JobRunner.class);
        JobDefinition definition = jobDefinition();
        when(runner.supports(definition)).thenReturn(false);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> new JobRunnerRegistry(List.of(runner)).resolve(definition)
        );

        assertEquals("No runner for job type ROUTE_APP", exception.getMessage());
    }

    private static JobDefinition jobDefinition() {
        return new JobDefinition(
            "job-1",
            1,
            JobType.ROUTE_APP,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            null,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }
}
