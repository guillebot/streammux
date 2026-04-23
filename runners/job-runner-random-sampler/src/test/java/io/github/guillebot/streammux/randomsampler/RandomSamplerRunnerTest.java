package io.github.guillebot.streammux.randomsampler;

import io.github.guillebot.streammux.contracts.config.RandomSamplerConfig;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import io.github.guillebot.streammux.randomsampler.config.RandomSamplerTopologyFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomSamplerRunnerTest {

    @Test
    void supportsRandomSamplerJobsOnly() {
        RandomSamplerRunner runner = new RandomSamplerRunner(new RandomSamplerTopologyFactory());
        assertTrue(runner.supports(randomSamplerJob()));
        assertFalse(runner.supports(routeAppJob()));
    }

    @Test
    void statusForUnknownJobIsStopped() {
        RandomSamplerRunner runner = new RandomSamplerRunner(new RandomSamplerTopologyFactory());
        var status = runner.status("missing");
        assertEquals("missing", status.jobId());
        assertEquals(RuntimeState.STOPPED, status.state());
        assertEquals(HealthState.UNKNOWN, status.health());
    }

    @Test
    void stopForUnknownJobIsNoOp() {
        RandomSamplerRunner runner = new RandomSamplerRunner(new RandomSamplerTopologyFactory());
        runner.stop("never-started");
    }

    private static JobDefinition randomSamplerJob() {
        return new JobDefinition(
            "job-1",
            1,
            JobType.RANDOM_SAMPLER,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            null,
            new RandomSamplerConfig("in", "out", 1.0, Map.of()),
            null,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }

    private static JobDefinition routeAppJob() {
        return new JobDefinition(
            "job-2",
            1,
            JobType.ROUTE_APP,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            null,
            null,
            null,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }
}
