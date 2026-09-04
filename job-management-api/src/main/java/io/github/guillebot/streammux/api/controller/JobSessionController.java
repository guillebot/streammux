package io.github.guillebot.streammux.api.controller;

import io.github.guillebot.streammux.api.service.PlatformHealthIssuesService;
import io.github.guillebot.streammux.api.service.PlatformHealthIssuesService.HealthIssuesSnapshot;
import io.github.guillebot.streammux.api.service.PlatformHealthIssuesService.HealthSummary;
import io.github.guillebot.streammux.api.service.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/jobs/meta")
@ConditionalOnProperty(name = "streammux.auth.enabled", havingValue = "false", matchIfMissing = true)
public class JobSessionController {
    private final RequestActorResolver actorResolver;

    public JobSessionController(RequestActorResolver actorResolver) {
        this.actorResolver = actorResolver;
    }

    @GetMapping("/session")
    public SessionInfo session(HttpServletRequest request) {
        String username = actorResolver.resolve(request);
        return new SessionInfo(username, "admin", "PROXY", null);
    }

    public record SessionInfo(String username, String role, String authType, String avatarUrl) {}
}
