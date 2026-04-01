package io.github.guillebot.streammux.routeapp;

import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LagMetrics;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import io.github.guillebot.streammux.contracts.model.WorkerMetadata;
import io.github.guillebot.streammux.contracts.spi.JobRunner;
import io.github.guillebot.streammux.routeapp.config.RouteAppTopologyFactory;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RouteAppRunner implements JobRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(RouteAppRunner.class);

    private final RouteAppTopologyFactory topologyFactory;
    private final Map<String, KafkaStreams> runningJobs = new ConcurrentHashMap<>();

    public RouteAppRunner(RouteAppTopologyFactory topologyFactory) { this.topologyFactory = topologyFactory; }

    @Override public boolean supports(JobDefinition jobDefinition) { return jobDefinition.jobType() == JobType.ROUTE_APP; }

    @Override
    public void start(JobDefinition jobDefinition, long leaseEpoch) {
        stop(jobDefinition.jobId());
        Topology topology = topologyFactory.build(jobDefinition);
        Properties properties = topologyFactory.properties(jobDefinition, leaseEpoch);
        KafkaStreams streams = new KafkaStreams(topology, properties);
        streams.start();
        runningJobs.put(jobDefinition.jobId(), streams);
        LOGGER.info("Started route-app job {} at lease epoch {}", jobDefinition.jobId(), leaseEpoch);
    }

    @Override
    public void stop(String jobId) {
        KafkaStreams streams = runningJobs.remove(jobId);
        if (streams != null) { streams.close(); LOGGER.info("Stopped route-app job {}", jobId); }
    }

    @Override
    public JobRuntimeStatus status(String jobId) {
        KafkaStreams streams = runningJobs.get(jobId);
        RuntimeState runtimeState = streams == null ? RuntimeState.STOPPED : RuntimeState.RUNNING;
        HealthState healthState = streams == null ? HealthState.UNKNOWN : HealthState.HEALTHY;
        return new JobRuntimeStatus(jobId, 0, runtimeState, healthState, Instant.now(), new WorkerMetadata(jobId, "route-app", runtimeState.name(), Map.of()), null, new LagMetrics(0, 0, 0));
    }
}
