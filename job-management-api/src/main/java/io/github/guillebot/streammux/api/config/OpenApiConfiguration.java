package io.github.guillebot.streammux.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI streammuxOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Streammux Job Management API")
                .description(
                    "REST API for job definitions and lifecycle commands. "
                        + "The service validates payloads, maintains a Kafka-projected read model, and publishes definitions, commands, and events to Kafka."
                )
                .version("0.1.0-SNAPSHOT")
                .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0.html")));
    }
}
