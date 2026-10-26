package com.gewu.application.vcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.gewu.application.vcs.dto.CiStatusDTO;
import com.gewu.application.vcs.dto.IssueDTO;
import com.gewu.application.vcs.dto.MergeRequestDTO;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GitLab 适配器（REST v4，gitlab.com / 自建实例）。
 * 项目 ID 使用 URL 编码的 owner/repo 路径（支持 subgroup）。
 */
public class GitlabAdapter implements PlatformAdapter {

    @Override
    public PlatformType platform() {
        return PlatformType.GITLAB;
    }

    private Map<String, String> headers(ProjectRef ref) {
        return Map.of("PRIVATE-TOKEN", ref.getToken());
    }

    private String api(ProjectRef ref, String path) {
        String enc = URLEncoder.encode(ref.getRepoPath(), StandardCharsets.UTF_8).replace("+", "%20");
        return ref.getApiBase() + "/projects/" + enc + path;
    }

    @Override
    public List<MergeRequestDTO> listMRs(ProjectRef ref, String state) {
        String st = switch (state == null ? "all" : state) {
            case "open" -> "opened";
            case "merged" -> "merged";
            case "closed" -> "closed";
            default -> "all";
        };
        JsonNode arr = VcsHttp.get(api(ref, "/merge_requests?state=" + st + "&per_page=30"), headers(ref));
        List<MergeRequestDTO> list = new ArrayList<>();
        for (JsonNode n : arr) {
            list.add(toMR(n));
        }
        return list;
    }

    @Override
    public MergeRequestDTO createMR(ProjectRef ref, String sourceBranch, String targetBranch, String title, String description) {
        JsonNode n = VcsHttp.post(api(ref, "/merge_requests"), headers(ref), Map.of(
                "source_branch", sourceBranch,
                "target_branch", targetBranch,
                "title", title,
                "description", description != null ? description : ""));
        return toMR(n);
    }

    @Override
    public MergeRequestDTO getMR(ProjectRef ref, long number) {
        return toMR(VcsHttp.get(api(ref, "/merge_requests/" + number), headers(ref)));
    }

    @Override
    public CiStatusDTO ciOf(ProjectRef ref, String refName) {
        CiStatusDTO dto = new CiStatusDTO();
        dto.setRef(refName);
        dto.setOverall("none");
        try {
            // 最新 pipeline（按 ref 过滤）
            JsonNode arr = VcsHttp.get(api(ref, "/pipelines?ref=" + refName + "&per_page=1"), headers(ref));
            if (arr.isArray() && !arr.isEmpty()) {
                JsonNode p = arr.get(0);
                String status = p.path("status").asText("");
                dto.setOverall(switch (status) {
                    case "success" -> "success";
                    case "failed" -> "failure";
                    case "running", "pending", "waiting", "preparing", "created" -> "pending";
                    case "canceled", "skipped" -> "canceled";
                    default -> "none";
                });
                dto.setWebUrl(p.path("web_url").asText(null));
                CiStatusDTO.Item item = new CiStatusDTO.Item();
                item.setName("pipeline #" + p.path("id").asText());
                item.setState(status);
                item.setWebUrl(p.path("web_url").asText(null));
                dto.getItems().add(item);
            }
        } catch (Exception ignored) {
            dto.setOverall("none");
        }
        return dto;
    }

    @Override
    public List<IssueDTO> listIssues(ProjectRef ref, String state) {
        String st = switch (state == null ? "all" : state) {
            case "open" -> "opened";
            case "closed" -> "closed";
            default -> "all";
        };
        JsonNode arr = VcsHttp.get(api(ref, "/issues?state=" + st + "&per_page=30"), headers(ref));
        List<IssueDTO> list = new ArrayList<>();
        for (JsonNode n : arr) {
            list.add(toIssue(n));
        }
        return list;
    }

    @Override
    public IssueDTO getIssue(ProjectRef ref, long number) {
        IssueDTO issue = toIssue(VcsHttp.get(api(ref, "/issues/" + number), headers(ref)));
        JsonNode arr = VcsHttp.get(api(ref, "/issues/" + number + "/notes?sort=asc&per_page=50"), headers(ref));
        for (JsonNode n : arr) {
            if (n.path("system").asBoolean(false)) {
                continue; // 系统动态（状态变更等）不入评论流
            }
            IssueDTO.Comment c = new IssueDTO.Comment();
            c.setAuthor(n.path("author").path("username").asText());
            c.setBody(n.path("body").asText());
            c.setCreatedAt(GithubAdapter.parseTime(n.path("created_at").asText(null)));
            issue.getComments().add(c);
        }
        return issue;
    }

    @Override
    public void commentIssue(ProjectRef ref, long number, String body) {
        VcsHttp.post(api(ref, "/issues/" + number + "/notes"), headers(ref), Map.of("body", body));
    }

    @Override
    public void commentMR(ProjectRef ref, long number, String body) {
        VcsHttp.post(api(ref, "/merge_requests/" + number + "/notes"), headers(ref), Map.of("body", body));
    }

    @Override
    public List<com.gewu.application.vcs.dto.ReleaseDTO> listReleases(ProjectRef ref) {
        JsonNode arr = VcsHttp.get(api(ref, "/releases?per_page=30"), headers(ref));
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
                "description", notes != null ? notes : "",
                "prerelease", prerelease));
        return toRelease(n);
    }

    private com.gewu.application.vcs.dto.ReleaseDTO toRelease(JsonNode n) {
        com.gewu.application.vcs.dto.ReleaseDTO r = new com.gewu.application.vcs.dto.ReleaseDTO();
        r.setTagName(n.path("tag_name").asText());
        r.setName(n.path("name").asText());
        r.setNotes(n.path("description").asText(null));
        r.setState("published");
        r.setPrerelease(n.path("upcoming_release").asBoolean(false));
        r.setWebUrl(n.path("_links").path("self").asText(n.path("web_url").asText(null)));
        r.setCreatedAt(GithubAdapter.parseTime(n.path("created_at").asText(null)));
        for (JsonNode a : n.path("assets").path("links")) {
            r.getAssets().add(a.path("name").asText());
        }
        return r;
    }

    private MergeRequestDTO toMR(JsonNode n) {
        MergeRequestDTO mr = new MergeRequestDTO();
        mr.setNumber(n.path("iid").asLong());
        mr.setTitle(n.path("title").asText());
        mr.setDescription(n.path("description").asText(null));
        mr.setState(n.path("state").asText().equals("opened") ? "open" : n.path("state").asText());
        mr.setSourceBranch(n.path("source_branch").asText());
        mr.setTargetBranch(n.path("target_branch").asText());
        mr.setWebUrl(n.path("web_url").asText());
        mr.setAuthor(n.path("author").path("username").asText(null));
        mr.setCreatedAt(GithubAdapter.parseTime(n.path("created_at").asText(null)));
        mr.setHeadSha(n.path("sha").asText(null));
        return mr;
    }

    private IssueDTO toIssue(JsonNode n) {
        IssueDTO issue = new IssueDTO();
        issue.setNumber(n.path("iid").asLong());
        issue.setTitle(n.path("title").asText());
        issue.setDescription(n.path("description").asText(null));
        issue.setState(n.path("state").asText().equals("opened") ? "open" : n.path("state").asText());
        issue.setWebUrl(n.path("web_url").asText());
        issue.setAuthor(n.path("author").path("username").asText(null));
        issue.setCreatedAt(GithubAdapter.parseTime(n.path("created_at").asText(null)));
        return issue;
    }
}
