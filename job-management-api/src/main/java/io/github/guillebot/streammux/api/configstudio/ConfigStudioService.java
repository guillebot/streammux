package io.github.guillebot.streammux.api.configstudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.guillebot.streammux.api.configstudio.GitLabConfigClient.CommitAction;
import io.github.guillebot.streammux.api.configstudio.GitLabConfigClient.TreeEntry;
import io.github.guillebot.streammux.api.service.JobService;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "streammux.config-studio.enabled", havingValue = "true")
public class ConfigStudioService {
    private static final DateTimeFormatter BRANCH_TS =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final ConfigStudioProperties props;
    private final String deploymentLabel;
    private final GitLabConfigClient gitlab;
    private final JobService jobService;
    private final ConfigStudioSyncStateRepository syncState;
    private final ObjectMapper objectMapper;

    public ConfigStudioService(
        ConfigStudioProperties props,
        @Value("${STREAMMUX_DEPLOYMENT_LABEL:}") String deploymentLabel,
        GitLabConfigClient gitlab,
        JobService jobService,
        ConfigStudioSyncStateRepository syncState,
        ObjectMapper objectMapper
    ) {
        this.props = props;
        this.deploymentLabel = deploymentLabel == null ? "" : deploymentLabel.trim();
        this.gitlab = gitlab;
        this.jobService = jobService;
        this.syncState = syncState;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> status() {
        String gitHeadSha = null;
        if (props.ready()) {
            try {
                gitHeadSha = gitlab.resolveCommitSha(props.defaultBranch());
            } catch (RuntimeException ignored) {
                // GitLab unreachable
            }
        }
        Map<String, Object> lastSync = new LinkedHashMap<>();
        for (var entry : syncState.findAllByEnvironment().entrySet()) {
            lastSync.put(entry.getKey(), Map.of(
                "gitSha", entry.getValue().lastGitSha(),
                "syncedAt", entry.getValue().syncedAt()
            ));
        }
        return Map.of(
            "enabled", props.enabled(),
            "ready", props.ready(),
            "configurationIssues", props.configurationIssues(),
            "allowedEnvironments", props.allowedEnvironments(),
            "defaultEnvironment", props.resolvedDefaultEnvironment(deploymentLabel),
            "gitlabProjectUrl", props.gitlabProjectUrl(),
            "gitHeadSha", gitHeadSha == null ? "" : gitHeadSha,
            "lastSyncByEnv", lastSync
        );
    }

    public Map<String, Object> validate(String environment, String ref) {
        String env = normalizeEnv(environment);
        String gitRef = ref == null || ref.isBlank() ? props.defaultBranch() : ref.trim();
        List<String> issues = new ArrayList<>();
        for (String path : listJobPaths(env, gitRef)) {
            try {
                JobDefinition definition = readJob(gitRef, path);
                jobService.validate(definition);
            } catch (Exception ex) {
                issues.add(path + ": " + ex.getMessage());
            }
        }
        return Map.of("environment", env, "ref", gitRef, "valid", issues.isEmpty(), "issues", issues);
    }

    public Map<String, Object> sync(String environment, String ref, boolean dryRun) {
        if (!props.ready()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Config Studio is not configured");
        }
        String env = normalizeEnv(environment);
        String gitRef = ref == null || ref.isBlank() ? props.defaultBranch() : ref.trim();
        List<String> paths = listJobPaths(env, gitRef);
        List<String> applied = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        Set<String> gitJobIds = new LinkedHashSet<>();

        for (String path : paths) {
            JobDefinition definition = readJob(gitRef, path);
            gitJobIds.add(definition.jobId());
            if (!dryRun) {
                if (jobService.listJobs().stream().anyMatch(j -> j.jobId().equals(definition.jobId()))) {
                    jobService.updateJob(definition.jobId(), definition);
                } else {
                    jobService.createJob(definition);
                }
            }
            applied.add(definition.jobId());
        }

        for (JobDefinition live : jobService.listJobs()) {
            if (live.desiredState() == DesiredJobState.DELETED) {
                continue;
            }
            if (!gitJobIds.contains(live.jobId())) {
                if (!dryRun) {
                    jobService.deleteJob(live.jobId());
                }
                deleted.add(live.jobId());
            }
        }

        if (!dryRun) {
            syncState.save(env, gitlab.resolveCommitSha(gitRef));
        }
        return Map.of(
            "dryRun", dryRun,
            "environment", env,
            "ref", gitRef,
            "applied", applied,
            "deleted", deleted
        );
    }

    public Map<String, Object> submit(String environment, String message) {
        if (!props.ready()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Config Studio is not configured");
        }
        String env = normalizeEnv(environment);
        String branch = "config-studio/" + env + "/" + BRANCH_TS.format(Instant.now());
        gitlab.createBranch(branch, props.defaultBranch());
        List<CommitAction> actions = new ArrayList<>();
        for (JobDefinition job : jobService.listJobs()) {
            if (job.desiredState() == DesiredJobState.DELETED) {
                continue;
            }
            String path = env + "/jobs/" + job.jobId() + ".json";
            try {
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(job);
                String action = gitlab.fileExists(path, branch) ? "update" : "create";
                actions.add(new CommitAction(action, path, json));
            } catch (Exception ex) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize " + job.jobId(), ex);
            }
        }
        if (actions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No jobs to export");
        }
        String commitMessage = message == null || message.isBlank()
            ? "Config Studio export " + env + " @ " + Instant.now()
            : message.trim();
        gitlab.commit(branch, commitMessage, actions);
        var mr = gitlab.createMergeRequest(branch, commitMessage, "Automated job definition backup from Streammux Config Studio.");
        return Map.of(
            "branch", branch,
            "mergeRequestIid", mr.iid(),
            "mergeRequestUrl", mr.webUrl() == null ? "" : mr.webUrl()
        );
    }

    private String normalizeEnv(String environment) {
        String env = environment == null || environment.isBlank()
            ? props.resolvedDefaultEnvironment(deploymentLabel)
            : environment.trim().toLowerCase(Locale.ROOT);
        if (!props.allowedEnvironments().contains(env)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Environment not allowed: " + env);
        }
        return env;
    }

    private List<String> listJobPaths(String environment, String ref) {
        String prefix = environment + "/jobs";
        return gitlab.listTree(prefix, ref).stream()
            .map(TreeEntry::path)
            .filter(path -> path.endsWith(".json"))
            .toList();
    }

    private JobDefinition readJob(String ref, String path) {
        try {
            String raw = gitlab.getFileRaw(path, ref);
            return objectMapper.readValue(raw, JobDefinition.class);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job file " + path + ": " + ex.getMessage());
        }
    }
}
