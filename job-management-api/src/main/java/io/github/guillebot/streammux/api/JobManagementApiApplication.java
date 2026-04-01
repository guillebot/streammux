package io.github.guillebot.streammux.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JobManagementApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobManagementApiApplication.class, args);
    }
}
