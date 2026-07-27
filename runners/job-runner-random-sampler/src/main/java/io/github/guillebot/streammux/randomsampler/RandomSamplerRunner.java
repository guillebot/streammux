package io.github.guillebot.streammux.randomsampler;

import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.runner.support.KafkaStreamsRunnerSupport;
import io.github.guillebot.streammux.contracts.spi.JobRunner;
import io.github.guillebot.streammux.randomsampler.config.RandomSamplerTopologyFactory;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Properties;

@Component
public class RandomSamplerRunner implements JobRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(RandomSamplerRunner.class);

    private final RandomSamplerTopologyFactory topologyFactory;
    private final KafkaStreamsRunnerSupport streamsSupport = new KafkaStreamsRunnerSupport();

    public RandomSamplerRunner(RandomSamplerTopologyFactory topologyFactory) {
        this.topologyFactory = topologyFactory;
    }

    @Override
    public boolean supports(JobDefinition jobDefinition) {
        return jobDefinition.jobType() == JobType.RANDOM_SAMPLER;
    }

    @Override
    public void start(JobDefinition jobDefinition, long leaseEpoch) {
        stop(jobDefinition.jobId());
        Topology topology = topologyFactory.build(jobDefinition);
        Properties properties = topologyFactory.properties(jobDefinition, leaseEpoch);
        KafkaStreams streams = new KafkaStreams(topology, properties);
        try {
            streams.start();
            streamsSupport.register(jobDefinition.jobId(), streams);
            LOGGER.info("Started random-sampler job {} at lease epoch {}", jobDefinition.jobId(), leaseEpoch);
        } catch (RuntimeException ex) {
            streamsSupport.recordStartFailure(jobDefinition.jobId(), ex);
            streams.close();
            LOGGER.error("Failed to start random-sampler job {} at lease epoch {}", jobDefinition.jobId(), leaseEpoch, ex);
            throw ex;
        }
    }

    @Override
    public void stop(String jobId) {
        streamsSupport.stop(jobId);
        LOGGER.info("Stopped random-sampler job {}", jobId);
    }

    @Override
    public JobRuntimeStatus status(String jobId) {
        return streamsSupport.status(jobId, "random-sampler");
    }
}
