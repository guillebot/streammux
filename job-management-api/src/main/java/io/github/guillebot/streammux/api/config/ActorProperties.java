package io.github.guillebot.streammux.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "streammux.actor")
public record ActorProperties(boolean trustedProxyHeadersEnabled) {
    public ActorProperties {
        // default on for OneLab-style Authelia behind Traefik/nginx
    }
}
