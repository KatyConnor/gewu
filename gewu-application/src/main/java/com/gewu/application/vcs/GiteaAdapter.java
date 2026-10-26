package com.gewu.application.vcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.gewu.application.vcs.dto.CiStatusDTO;
import com.gewu.application.vcs.dto.IssueDTO;
import com.gewu.application.vcs.dto.MergeRequestDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gitea 适配器（API v1，自建实例；API 形态与 GitHub 高度接近）。
 */
public class GiteaAdapter implements PlatformAdapter {

    @Override
    public PlatformType platform() {
        return PlatformType.GITEA;
    }

    private Map<String, String> headers(ProjectRef ref) {
        return Map.of("Authorization", "token " + ref.getToken());
    }

    private String api(ProjectRef ref, String path) {
        return ref.getApiBase() + "/repos/" + ref.getRepoPath() + path;
    }

    @Override
    public List<MergeRequestDTO> listMRs(ProjectRef ref, String state) {
        String st = "open".equals(state) || "closed".equals(state) || "all".equals(state) ? state : "all";
        JsonNode arr = VcsHttp.get(api(ref, "/pulls?state=" + st + "&limit=30"), headers(ref));
        List<MergeRequestDTO> list = new ArrayList<>();
        for (JsonNode n : arr) {
            list.add(toMR(n));
        }
        return list;
    }

    @Override
    public MergeRequestDTO createMR(ProjectRef ref, String sourceBranch, String targetBranch, String title, String description) {
        JsonNode n = VcsHttp.post(api(ref, "/pulls"), headers(ref), Map.of(
                "title", title,
                "head", sourceBranch,
                "base", targetBranch,
                "body", description != null ? description : ""));
        return toMR(n);
    }

    @Override
    public MergeRequestDTO getMR(ProjectRef ref, long number) {
        return toMR(VcsHttp.get(api(ref, "/pulls/" + number), headers(ref)));
    }

    @Override
    public CiStatusDTO ciOf(ProjectRef ref, String refName) {
        CiStatusDTO dto = new CiStatusDTO();
        dto.setRef(refName);
        dto.setOverall("none");
        try {
            JsonNode n = VcsHttp.get(api(ref, "/commits/" + refName + "/status"), headers(ref));
            String state = n.path("state").asText("");
            dto.setOverall(switch (state) {
                case "success" -> "success";
                case "failure", "error" -> "failure";
                case "pending", "warning" -> "pending";
                default -> "none";
            });
            for (JsonNode s : n.path("statuses")) {
                CiStatusDTO.Item item = new CiStatusDTO.Item();
                item.setName(s.path("context").asText());
                item.setState(s.path("status").asText());
                item.setWebUrl(s.path("target_url").asText(null));
                dto.getItems().add(item);
            }
            dto.setWebUrl(ref.getWebBase() + "/" + ref.getRepoPath() + "/commits/branch/" + refName);
        } catch (Exception ignored) {
        }
        return dto;
    }

    @Override
    public List<IssueDTO> listIssues(ProjectRef ref, String state) {
        JsonNode arr = VcsHttp.get(api(ref, "/issues?state=" + state + "&limit=30"), headers(ref));
        List<IssueDTO> list = new ArrayList<>();
        for (JsonNode n : arr) {
            if (n.hasNonNull("pull_request")) {
                continue; // Gitea issues 列表含 PR
            }
            list.add(toIssue(n));
        }
        return list;
    }

    @Override
    public IssueDTO getIssue(ProjectRef ref, long number) {
        IssueDTO issue = toIssue(VcsHttp.get(api(ref, "/issues/" + number), headers(ref)));
        JsonNode arr = VcsHttp.get(api(ref, "/issues/" + number + "/comments?limit=50"), headers(ref));
        for (JsonNode n : arr) {
            IssueDTO.Comment c = new IssueDTO.Comment();
            c.setAuthor(n.path("user").path("login").asText());
            c.setBody(n.path("body").asText());
            c.setCreatedAt(GithubAdapter.parseTime(n.path("created_at").asText(null)));
            issue.getComments().add(c);
        }
        return issue;
    }

    @Override
    public void commentIssue(ProjectRef ref, long number, String body) {
        VcsHttp.post(api(ref, "/issues/" + number + "/comments"), headers(ref), Map.of("body", body));
    }

    @Override
    public void commentMR(ProjectRef ref, long number, String body) {
        // Gitea PR 复用 Issue 评论通道（编号一致）
        VcsHttp.post(api(ref, "/issues/" + number + "/comments"), headers(ref), Map.of("body", body));
    }

    @Override
    public List<com.gewu.application.vcs.dto.ReleaseDTO> listReleases(ProjectRef ref) {
        JsonNode arr = VcsHttp.get(api(ref, "/releases?limit=30"), headers(ref));
        List<com.gewu.application.vcs.dto.ReleaseDTO> list = new ArrayList<>();
        for (JsonNode n : arr) {
            list.add(toRelease(n));
        }
        return list;
    }

    @Override
    public com.gewu.application.vcs.dto.ReleaseDTO createRelease(ProjectRef ref, String tagName, String name, String notes, boolean prerelease) {
        JsonNode n = VcsHttp.post(api(ref, "/releases"), headers(ref), Map.of(
                "tag_name", tagName,
                "name", name != null ? name : tagName,
                "body", notes != null ? notes : "",
                "prerelease", prerelease,
                "draft", false));
        return toRelease(n);
    }

    // ==================== P3：评审 / Deploy Key / Release 附件 ====================

    @Override
    public List<com.gewu.application.vcs.dto.ReviewDTO> reviews(ProjectRef ref, long mrNumber) {
        JsonNode arr = VcsHttp.get(api(ref, "/pulls/" + mrNumber + "/reviews?limit=50"), headers(ref));
        List<com.gewu.application.vcs.dto.ReviewDTO> list = new ArrayList<>();
        for (JsonNode n : arr) {
            com.gewu.application.vcs.dto.ReviewDTO r = new com.gewu.application.vcs.dto.ReviewDTO();
            String state = n.path("state").asText();
            r.setState(switch (state) {
                case "APPROVED" -> "APPROVED";
                case "REQUEST_CHANGES" -> "CHANGES_REQUESTED";
                default -> "COMMENTED";
            });
            r.setReviewer(n.path("user").path("login").asText());
            r.setBody(n.path("body").asText(null));
            r.setSubmittedAt(GithubAdapter.parseTime(n.path("submitted_at").asText(null)));
            list.add(r);
        }
        return list;
    }

    @Override
    public void submitReview(ProjectRef ref, long mrNumber, String event, String body) {
        // Gitea 事件值：APPROVED / REQUEST_CHANGES / COMMENT
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("event", "APPROVE".equals(event) ? "APPROVED" : event);
        if (body != null && !body.isBlank()) {
            payload.put("body", body);
        }
        VcsHttp.post(api(ref, "/pulls/" + mrNumber + "/reviews"), headers(ref), payload);
    }

    @Override
    public void registerDeployKey(ProjectRef ref, String title, String publicKey) {
        VcsHttp.post(api(ref, "/keys"), headers(ref),
                Map.of("title", title, "key", publicKey, "read_only", true));
    }

    @Override
    public String uploadReleaseAsset(ProjectRef ref, com.gewu.application.vcs.dto.ReleaseDTO release, String fileName, byte[] data) {
        VcsHttp.postMultipart(api(ref, "/releases/" + release.getId() + "/assets?name="
                + java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8)),
                headers(ref), "attachment", fileName, data);
        return fileName;
    }

    @Override
    public com.gewu.application.vcs.dto.CapabilitiesDTO capabilities() {
        com.gewu.application.vcs.dto.CapabilitiesDTO c = new com.gewu.application.vcs.dto.CapabilitiesDTO();
        c.setPlatform(platform().name().toLowerCase());
        c.setReviewWrite(true);
        c.setDeployKey(true);
        c.setReleaseAsset(true);
        return c;
    }

    private MergeRequestDTO toMR(JsonNode n) {
        MergeRequestDTO mr = new MergeRequestDTO();
        mr.setNumber(n.path("number").asLong());
        mr.setTitle(n.path("title").asText());
        mr.setDescription(n.path("body").asText(null));
        mr.setState(n.hasNonNull("merged") && n.path("merged").asBoolean(false) ? "merged" : n.path("state").asText());
        mr.setSourceBranch(n.path("head").path("ref").asText());
        mr.setTargetBranch(n.path("base").path("ref").asText());
        mr.setWebUrl(n.path("html_url").asText());
        mr.setAuthor(n.path("user").path("login").asText(null));
        mr.setCreatedAt(GithubAdapter.parseTime(n.path("created_at").asText(null)));
        mr.setHeadSha(n.path("head").path("sha").asText(null));
        return mr;
    }

    private IssueDTO toIssue(JsonNode n) {
        IssueDTO issue = new IssueDTO();
        issue.setNumber(n.path("number").asLong());
        issue.setTitle(n.path("title").asText());
        issue.setDescription(n.path("body").asText(null));
        issue.setState(n.path("state").asText());
        issue.setWebUrl(n.path("html_url").asText());
        issue.setAuthor(n.path("user").path("login").asText(null));
        issue.setCreatedAt(GithubAdapter.parseTime(n.path("created_at").asText(null)));
        return issue;
    }

    private com.gewu.application.vcs.dto.ReleaseDTO toRelease(JsonNode n) {
        com.gewu.application.vcs.dto.ReleaseDTO r = new com.gewu.application.vcs.dto.ReleaseDTO();
        r.setTagName(n.path("tag_name").asText());
        r.setName(n.path("name").asText());
        r.setNotes(n.path("body").asText(null));
        r.setState(n.path("draft").asBoolean(false) ? "draft" : "published");
        r.setPrerelease(n.path("prerelease").asBoolean(false));
        r.setWebUrl(n.path("html_url").asText(null));
        r.setCreatedAt(GithubAdapter.parseTime(n.path("created_at").asText(null)));
        for (JsonNode a : n.path("assets")) {
            r.getAssets().add(a.path("name").asText());
        }
        return r;
    }
}
