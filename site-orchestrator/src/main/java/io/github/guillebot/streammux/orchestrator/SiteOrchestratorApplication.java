package io.github.guillebot.streammux.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SiteOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SiteOrchestratorApplication.class, args);
    }
}
