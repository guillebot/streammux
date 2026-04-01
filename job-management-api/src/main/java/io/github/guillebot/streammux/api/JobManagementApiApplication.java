package io.github.guillebot.streammux.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableKafka
public class JobManagementApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobManagementApiApplication.class, args);
    }
}
