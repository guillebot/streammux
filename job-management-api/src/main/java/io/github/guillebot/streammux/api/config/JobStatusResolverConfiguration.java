package io.github.guillebot.streammux.api.config;

import io.github.guillebot.streammux.api.service.JobStatusResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JobStatusResolverConfiguration {

    @Bean
    JobStatusResolver jobStatusResolver() {
        return new JobStatusResolver();
    }
}
