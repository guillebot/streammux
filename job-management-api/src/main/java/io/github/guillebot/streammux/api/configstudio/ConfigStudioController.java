package io.github.guillebot.streammux.api.configstudio;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/config-studio")
@ConditionalOnProperty(name = "streammux.config-studio.enabled", havingValue = "true")
public class ConfigStudioController {
    private final ConfigStudioService service;

    public ConfigStudioController(ConfigStudioService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return service.status();
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, String> body) {
        return service.validate(body.get("environment"), body.get("ref"));
    }

    @PostMapping("/sync")
    public Map<String, Object> sync(
        @RequestParam(defaultValue = "true") boolean dryRun,
        @RequestBody Map<String, String> body
    ) {
        return service.sync(body.get("environment"), body.get("ref"), dryRun);
    }

    @PostMapping("/submit")
    public Map<String, Object> submit(@RequestBody Map<String, String> body) {
        return service.submit(body.get("environment"), body.get("message"));
    }
}
