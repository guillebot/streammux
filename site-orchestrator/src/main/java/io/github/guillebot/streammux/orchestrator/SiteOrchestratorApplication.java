package io.github.guillebot.streammux.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.github.guillebot.streammux")
@ConfigurationPropertiesScan
@EnableKafka
@EnableScheduling
public class SiteOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SiteOrchestratorApplication.class, args);
    }
}
