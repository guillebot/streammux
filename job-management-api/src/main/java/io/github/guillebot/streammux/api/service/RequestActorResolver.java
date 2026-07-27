package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.ActorProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class RequestActorResolver {
    private static final String HEADER_REMOTE_USER = "Remote-User";
    private static final String HEADER_REMOTE_EMAIL = "Remote-Email";
    private static final String HEADER_REMOTE_NAME = "Remote-Name";

    private final ActorProperties actorProperties;

    public RequestActorResolver(ActorProperties actorProperties) {
        this.actorProperties = actorProperties;
    }

    public String resolve(HttpServletRequest request) {
        if (actorProperties.trustedProxyHeadersEnabled()) {
            String remoteUser = trimToNull(request.getHeader(HEADER_REMOTE_USER));
            if (remoteUser != null) {
                return remoteUser;
            }
            String remoteEmail = trimToNull(request.getHeader(HEADER_REMOTE_EMAIL));
            if (remoteEmail != null) {
                return remoteEmail;
            }
            String remoteName = trimToNull(request.getHeader(HEADER_REMOTE_NAME));
            if (remoteName != null) {
                return remoteName;
            }
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getName() != null) {
            return authentication.getName();
        }
        return "unknown";
    }

    public String currentActor() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return resolve(servletRequestAttributes.getRequest());
        }
        return "system";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
