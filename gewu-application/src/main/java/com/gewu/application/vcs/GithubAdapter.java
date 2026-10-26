package com.gewu.application.vcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.gewu.application.vcs.dto.CiStatusDTO;
import com.gewu.application.vcs.dto.IssueDTO;
import com.gewu.application.vcs.dto.MergeRequestDTO;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GitHub 适配器（REST v3，github.com / GitHub Enterprise）。
 */
public class GithubAdapter implements PlatformAdapter {

    @Override
    public PlatformType platform() {
        return PlatformType.GITHUB;
    }

    private Map<String, String> headers(ProjectRef ref) {
        return Map.of(
                "Authorization", "Bearer " + ref.getToken(),
                "Accept", "application/vnd.github+json");
    }

    private String api(ProjectRef ref, String path) {
        return ref.getApiBase() + "/repos/" + ref.getRepoPath() + path;
    }

    @Override
    public List<MergeRequestDTO> listMRs(ProjectRef ref, String state) {
        String st = "all".equals(state) ? "all" : "open".equals(state) ? "open" : "closed";
        JsonNode arr = VcsHttp.get(api(ref, "/pulls?state=" + st + "&per_page=30"), headers(ref));
        List<MergeRequestDTO> list = new ArrayList<>();
        for (JsonNode n : arr) {
            MergeRequestDTO mr = new MergeRequestDTO();
            mr.setNumber(n.path("number").asLong());
            mr.setTitle(n.path("title").asText());
            mr.setDescription(n.path("body").asText(null));
            // merged_at 非空 = 已合并（列表态 GitHub 只有 open/closed）
            mr.setState(n.hasNonNull("merged_at") ? "merged" : n.path("state").asText());
            mr.setSourceBranch(n.path("head").path("ref").asText());
            mr.setTargetBranch(n.path("base").path("ref").asText());
            mr.setWebUrl(n.path("html_url").asText());
            mr.setAuthor(n.path("user").path("login").asText(null));
            mr.setCreatedAt(parseTime(n.path("created_at").asText(null)));
            mr.setHeadSha(n.path("head").path("sha").asText(null));
            list.add(mr);
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
        MergeRequestDTO mr = new MergeRequestDTO();
        mr.setNumber(n.path("number").asLong());
        mr.setTitle(n.path("title").asText());
        mr.setState("open");
        mr.setSourceBranch(n.path("head").path("ref").asText());
        mr.setTargetBranch(n.path("base").path("ref").asText());
        mr.setWebUrl(n.path("html_url").asText());
        return mr;
    }

    @Override
    public MergeRequestDTO getMR(ProjectRef ref, long number) {
        JsonNode n = VcsHttp.get(api(ref, "/pulls/" + number), headers(ref));
        MergeRequestDTO mr = new MergeRequestDTO();
        mr.setNumber(n.path("number").asLong());
        mr.setTitle(n.path("title").asText());
        mr.setDescription(n.path("body").asText(null));
        mr.setState(n.hasNonNull("merged_at") ? "merged" : n.path("state").asText());
        mr.setSourceBranch(n.path("head").path("ref").asText());
        mr.setTargetBranch(n.path("base").path("ref").asText());
        mr.setWebUrl(n.path("html_url").asText());
        mr.setAuthor(n.path("user").path("login").asText(null));
        mr.setCreatedAt(parseTime(n.path("created_at").asText(null)));
        mr.setHeadSha(n.path("head").path("sha").asText(null));
        return mr;
    }

    @Override
    public CiStatusDTO ciOf(ProjectRef ref, String refName) {
        CiStatusDTO dto = new CiStatusDTO();
        dto.setRef(refName);
        try {
            JsonNode n = VcsHttp.get(api(ref, "/commits/" + refName + "/status"), headers(ref));
            String state = n.path("state").asText("");
            dto.setOverall(switch (state) {
                case "success" -> "success";
                case "failure", "error" -> "failure";
                case "pending", "expected" -> "pending";
                default -> "none";
            });
            for (JsonNode s : n.path("statuses")) {
                CiStatusDTO.Item item = new CiStatusDTO.Item();
                item.setName(s.path("context").asText());
                item.setState(s.path("state").asText());
                item.setWebUrl(s.path("target_url").asText(null));
                dto.getItems().add(item);
            }
            dto.setWebUrl(ref.getWebBase() + "/" + ref.getRepoPath() + "/commits/" + refName);
        } catch (Exception e) {
            dto.setOverall("none");
        }
        return dto;
    }

    @Override
    public List<IssueDTO> listIssues(ProjectRef ref, String state) {
        // GitHub issues 列表含 PR —— 过滤带 pull_request 键的条目
        JsonNode arr = VcsHttp.get(api(ref, "/issues?state=" + state + "&per_page=30"), headers(ref));
        List<IssueDTO> list = new ArrayList<>();
        for (JsonNode n : arr) {
            if (n.has("pull_request")) {
                continue;
            }
            list.add(toIssue(n));
        }
        return list;
    }

    @Override
    public IssueDTO getIssue(ProjectRef ref, long number) {
        IssueDTO issue = toIssue(VcsHttp.get(api(ref, "/issues/" + number), headers(ref)));
        JsonNode arr = VcsHttp.get(api(ref, "/issues/" + number + "/comments?per_page=50"), headers(ref));
        for (JsonNode n : arr) {
            IssueDTO.Comment c = new IssueDTO.Comment();
            c.setAuthor(n.path("user").path("login").asText());
            c.setBody(n.path("body").asText());
            c.setCreatedAt(parseTime(n.path("created_at").asText(null)));
            issue.getComments().add(c);
        }
        return issue;
    }

    private IssueDTO toIssue(JsonNode n) {
        IssueDTO issue = new IssueDTO();
        issue.setNumber(n.path("number").asLong());
        issue.setTitle(n.path("title").asText());
        issue.setDescription(n.path("body").asText(null));
        issue.setState(n.path("state").asText());
        issue.setWebUrl(n.path("html_url").asText());
        issue.setAuthor(n.path("user").path("login").asText(null));
        issue.setCreatedAt(parseTime(n.path("created_at").asText(null)));
        return issue;
    }

    static Long parseTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
