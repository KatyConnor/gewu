package com.gewu.application.vcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.gewu.application.vcs.dto.CiStatusDTO;
import com.gewu.application.vcs.dto.IssueDTO;
import com.gewu.application.vcs.dto.MergeRequestDTO;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gitee 适配器（API v5，gitee.com）。
 * 认证走 access_token 查询参数（个人令牌最可靠形态）。
 */
public class GiteeAdapter implements PlatformAdapter {

    @Override
    public PlatformType platform() {
        return PlatformType.GITEE;
    }

    private String api(ProjectRef ref, String path) {
        String sep = path.contains("?") ? "&" : "?";
        return ref.getApiBase() + "/repos/" + ref.getRepoPath() + path + sep + "access_token=" + ref.getToken();
    }

    @Override
    public List<MergeRequestDTO> listMRs(ProjectRef ref, String state) {
        String st = switch (state == null ? "all" : state) {
            case "open" -> "open";
            case "merged" -> "merged";
            case "closed" -> "closed";
            default -> "all";
        };
        JsonNode arr = VcsHttp.get(api(ref, "/pulls?state=" + st + "&per_page=30"), Map.of());
        List<MergeRequestDTO> list = new ArrayList<>();
        for (JsonNode n : arr) {
            list.add(toMR(n));
        }
        return list;
    }

    @Override
    public MergeRequestDTO createMR(ProjectRef ref, String sourceBranch, String targetBranch, String title, String description) {
        JsonNode n = VcsHttp.post(api(ref, "/pulls"), Map.of(), Map.of(
                "title", title,
                "head", sourceBranch,
                "base", targetBranch,
                "body", description != null ? description : ""));
        return toMR(n);
    }

    @Override
    public MergeRequestDTO getMR(ProjectRef ref, long number) {
        return toMR(VcsHttp.get(api(ref, "/pulls/" + number), Map.of()));
    }

    @Override
    public CiStatusDTO ciOf(ProjectRef ref, String refName) {
        CiStatusDTO dto = new CiStatusDTO();
        dto.setRef(refName);
        dto.setOverall("none");
        try {
            JsonNode arr = VcsHttp.get(api(ref, "/statuses/" + refName), Map.of());
            if (arr.isArray() && !arr.isEmpty()) {
                JsonNode s = arr.get(0);
                String state = s.path("state").asText("");
                dto.setOverall(switch (state) {
                    case "success" -> "success";
                    case "failure", "error" -> "failure";
                    case "pending" -> "pending";
                    default -> "none";
                });
                dto.setWebUrl(s.path("target_url").asText(null));
                CiStatusDTO.Item item = new CiStatusDTO.Item();
                item.setName(s.path("context").asText());
                item.setState(state);
                item.setWebUrl(s.path("target_url").asText(null));
                dto.getItems().add(item);
            }
        } catch (Exception ignored) {
        }
        return dto;
    }

    @Override
    public List<IssueDTO> listIssues(ProjectRef ref, String state) {
        String st = "closed".equals(state) ? "closed" : "open".equals(state) ? "open" : "all";
        JsonNode arr = VcsHttp.get(api(ref, "/issues?state=" + st + "&per_page=30"), Map.of());
        List<IssueDTO> list = new ArrayList<>();
        for (JsonNode n : arr) {
            list.add(toIssue(n));
        }
        return list;
    }

    @Override
    public IssueDTO getIssue(ProjectRef ref, long number) {
        IssueDTO issue = toIssue(VcsHttp.get(api(ref, "/issues/" + number), Map.of()));
        JsonNode arr = VcsHttp.get(api(ref, "/issues/" + number + "/comments?per_page=50"), Map.of());
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
        VcsHttp.post(api(ref, "/issues/" + number + "/comments"), Map.of(), Map.of("body", body));
    }

    @Override
    public void commentMR(ProjectRef ref, long number, String body) {
        VcsHttp.post(api(ref, "/pulls/" + number + "/comments"), Map.of(), Map.of("body", body));
    }

    @Override
    public List<com.gewu.application.vcs.dto.ReleaseDTO> listReleases(ProjectRef ref) {
        JsonNode arr = VcsHttp.get(api(ref, "/releases?per_page=30"), Map.of());
        List<com.gewu.application.vcs.dto.ReleaseDTO> list = new ArrayList<>();
        for (JsonNode n : arr) {
            list.add(toRelease(n));
        }
        return list;
    }

    @Override
    public com.gewu.application.vcs.dto.ReleaseDTO createRelease(ProjectRef ref, String tagName, String name, String notes, boolean prerelease) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tag_name", tagName);
        body.put("name", name != null ? name : tagName);
        body.put("body", notes != null ? notes : "");
        if (prerelease) {
            body.put("prerelease", true);
        }
        return toRelease(VcsHttp.post(api(ref, "/releases"), Map.of(), body));
    }

    // ==================== P3 ====================

    @Override
    public List<com.gewu.application.vcs.dto.ReviewDTO> reviews(ProjectRef ref, long mrNumber) {
        return new ArrayList<>(); // Gitee 评审 API 不成熟（能力矩阵关闭，主流程走网页）
    }

    @Override
    public void submitReview(ProjectRef ref, long mrNumber, String event, String body) {
        throw BusinessException.of(ResultCode.PARAM_INVALID, "Gitee 暂不支持平台内评审提交，请前往平台网页操作");
    }

    @Override
    public void registerDeployKey(ProjectRef ref, String title, String publicKey) {
        VcsHttp.post(api(ref, "/keys"), Map.of(), Map.of("key", publicKey, "title", title));
    }

    @Override
    public String uploadReleaseAsset(ProjectRef ref, com.gewu.application.vcs.dto.ReleaseDTO release, String fileName, byte[] data) {
        VcsHttp.postMultipart(api(ref, "/releases/" + release.getId() + "/attach_files"),
                Map.of(), "file", fileName, data);
        return fileName;
    }

    @Override
    public com.gewu.application.vcs.dto.CapabilitiesDTO capabilities() {
        com.gewu.application.vcs.dto.CapabilitiesDTO c = new com.gewu.application.vcs.dto.CapabilitiesDTO();
        c.setPlatform(platform().name().toLowerCase());
        c.setReviewWrite(false);
        c.setDeployKey(true);
        c.setReleaseAsset(true);
        return c;
    }

    private MergeRequestDTO toMR(JsonNode n) {
        MergeRequestDTO mr = new MergeRequestDTO();
        mr.setNumber(n.path("number").asLong());
        mr.setTitle(n.path("title").asText());
        mr.setDescription(n.path("body").asText(null));
        mr.setState(n.path("state").asText());
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
        r.setState("published");
        r.setPrerelease(n.path("prerelease").asBoolean(false));
        r.setWebUrl(n.path("html_url").asText(null));
        r.setCreatedAt(GithubAdapter.parseTime(n.path("created_at").asText(null)));
        return r;
    }
}
