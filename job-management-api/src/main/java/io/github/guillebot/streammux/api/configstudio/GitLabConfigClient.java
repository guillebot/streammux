/*
 * (c) Optimum 2026
 * Guillermo Schimmel
 */

package io.github.guillebot.streammux.api.configstudio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Minimal GitLab REST client for branch + commit + merge-request flows.
 */
@Component
@ConditionalOnProperty(name = "streammux.config-studio.enabled", havingValue = "true")
public class GitLabConfigClient {

    private final ConfigStudioProperties props;
    private final RestClient client;

    public GitLabConfigClient(ConfigStudioProperties props) {
        this.props = props;
        this.client = RestClient.builder()
                .baseUrl(normalizeBase(props.gitlabBaseUrl()))
                .defaultHeader("PRIVATE-TOKEN", props.gitlabToken() == null ? "" : props.gitlabToken())
                .build();
    }

    void createBranch(String branch, String ref) {
        client.post()
                .uri("/projects/{id}/repository/branches", projectId())
                .body(Map.of("branch", branch, "ref", ref))
                .retrieve()
                .toBodilessEntity();
    }

    boolean fileExists(String path, String ref) {
        try {
            client.get()
                    .uri("/projects/{id}/repository/files/{path}?ref={ref}",
                            projectId(), path, ref)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            throw ex;
        }
    }

    void commit(String branch, String message, List<CommitAction> actions) {
        client.post()
                .uri("/projects/{id}/repository/commits", projectId())
                .body(Map.of(
                        "branch", branch,
                        "commit_message", message,
                        "actions", actions))
                .retrieve()
                .toBodilessEntity();
    }

    MergeRequest createMergeRequest(String sourceBranch, String title, String description) {
        return client.post()
                .uri("/projects/{id}/merge_requests", projectId())
                .body(Map.of(
                        "source_branch", sourceBranch,
                        "target_branch", props.defaultBranch(),
                        "title", title,
                        "description", description == null ? "" : description,
                        "remove_source_branch", true))
                .retrieve()
                .body(MergeRequest.class);
    }

    /**
     * Lists repository tree entries under {@code path} at {@code ref}.
     * Uses {@code recursive=true} so nested YAML files are included.
     */
    List<TreeEntry> listTree(String path, String ref) {
        TreeEntry[] page = client.get()
                .uri(uri -> uri.path("/projects/{id}/repository/tree")
                        .queryParam("path", path)
                        .queryParam("ref", ref)
                        .queryParam("recursive", true)
                        .queryParam("per_page", 100)
                        .build(projectId()))
                .retrieve()
                .body(TreeEntry[].class);
        return page == null ? List.of() : List.of(page);
    }

    /** Raw file bytes from the repository at {@code ref}. */
    String getFileRaw(String path, String ref) {
        return client.get()
                .uri("/projects/{id}/repository/files/{path}/raw?ref={ref}",
                        projectId(), path, ref)
                .retrieve()
                .body(String.class);
    }

    /** Lists repository branches (newest activity first when GitLab supports it). */
    List<BranchListItem> listBranches() {
        BranchListItem[] page = client.get()
                .uri(uri -> uri.path("/projects/{id}/repository/branches")
                        .queryParam("per_page", 100)
                        .build(projectId()))
                .retrieve()
                .body(BranchListItem[].class);
        return page == null ? List.of() : List.of(page);
    }

    /** Commit SHA for {@code ref} (branch name or tag). */
    String resolveCommitSha(String ref) {
        Commit commit = client.get()
                .uri("/projects/{id}/repository/commits/{ref}", projectId(), ref)
                .retrieve()
                .body(Commit.class);
        if (commit == null || commit.id() == null || commit.id().isBlank()) {
            throw new IllegalStateException("GitLab did not return a commit SHA for ref " + ref);
        }
        return commit.id();
    }

    /** Lists merge requests filtered by {@code state} (opened, merged, closed, all). */
    List<MergeRequestListItem> listMergeRequests(String state) {
        MergeRequestListItem[] page = client.get()
                .uri(uri -> uri.path("/projects/{id}/merge_requests")
                        .queryParam("state", state == null || state.isBlank() ? "opened" : state)
                        .queryParam("target_branch", props.defaultBranch())
                        .queryParam("order_by", "updated_at")
                        .queryParam("sort", "desc")
                        .queryParam("per_page", 20)
                        .build(projectId()))
                .retrieve()
                .body(MergeRequestListItem[].class);
        return page == null ? List.of() : List.of(page);
    }

    /**
     * GitLab project id or URL-encoded path segment. RestClient URI templates encode
     * path variables once; pre-encoding here caused {@code %2F} → {@code %252F} and
     * GitLab 404 Project Not Found.
     */
    private String projectId() {
        return props.gitlabProjectId();
    }

    private static String normalizeBase(String base) {
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/api/v4";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MergeRequest(
            @JsonProperty("web_url") String webUrl,
            @JsonProperty("iid") int iid
    ) {}

    public record CommitAction(
            String action,
            @JsonProperty("file_path") String filePath,
            String content
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TreeEntry(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("type") String type,
            @JsonProperty("path") String path
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Commit(
            @JsonProperty("id") String id
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BranchListItem(
            @JsonProperty("name") String name,
            @JsonProperty("default") boolean defaultBranch,
            @JsonProperty("protected") boolean protectedBranch,
            @JsonProperty("commit") BranchCommit commit
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BranchCommit(
            @JsonProperty("id") String id,
            @JsonProperty("short_id") String shortId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MergeRequestListItem(
            @JsonProperty("iid") int iid,
            @JsonProperty("title") String title,
            @JsonProperty("state") String state,
            @JsonProperty("source_branch") String sourceBranch,
            @JsonProperty("web_url") String webUrl,
            @JsonProperty("author") MergeRequestAuthor author,
            @JsonProperty("updated_at") String updatedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MergeRequestAuthor(
            @JsonProperty("username") String username
    ) {}
}
