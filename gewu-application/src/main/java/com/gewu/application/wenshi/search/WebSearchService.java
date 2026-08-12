package com.gewu.application.wenshi.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 网络搜索服务 — 通过 SearXNG JSON API 执行搜索。
 * <p>
 * SearXNG 是一个开源的隐私搜索引擎聚合器，支持 JSON 格式输出。
 * 默认部署地址 {@code http://searxng:8080}，可通过配置覆盖。
 * <p>
 * 调用协议：{@code GET /search?format=json&q={query}&categories=general&language=zh-CN}
 * <p>
 * 搜索结果经过去重、裁剪后封装为 {@link WebSearchFragment} 列表，
 * 交由 {@link WebResultVerifier} 做正确性判断。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
public class WebSearchService {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** SearXNG 服务地址 */
    @Value("${gewu.wenshi.web-search.base-url:http://searxng:8080}")
    private String searxngBaseUrl;

    /** 最大返回结果数 */
    @Value("${gewu.wenshi.web-search.max-results:5}")
    private int maxResults;

    /** HTTP 请求超时（毫秒） */
    @Value("${gewu.wenshi.web-search.timeout-ms:8000}")
    private int timeoutMs;

    /** 单条摘要裁剪最大长度 */
    @Value("${gewu.wenshi.web-search.snippet-max-length:200}")
    private int snippetMaxLength;

    /** SearXNG 不需要 API key，但生产环境可配置实例密钥 */
    @Value("${gewu.wenshi.web-search.api-key:}")
    private String apiKey;

    /**
     * 执行网络搜索。
     * <p>
     * 调用 SearXNG JSON API，解析结果并去重裁剪。
     * 搜索失败时返回 success=false 的空结果，不抛异常（由调用方兜底回退 LLM）。
     *
     * @param query 搜索关键词
     * @return 搜索结果集（始终非 null，失败时 success=false）
     * @since 1.0.0
     */
    public WebSearchResult search(String query) {
        if (query == null || query.isBlank()) {
            return WebSearchResult.builder().query(query).success(false)
                    .errorMessage("查询为空").build();
        }

        long startTime = System.currentTimeMillis();
        try {
            // 构建 SearXNG JSON API 请求
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = String.format("%s/search?format=json&q=%s&categories=general&language=zh-CN&safesearch=1",
                    searxngBaseUrl, encodedQuery);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Accept", "application/json")
                    .GET();

            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = HTTP_CLIENT.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("WebSearchService: SearXNG returned status={}, query={}", response.statusCode(), query);
                return WebSearchResult.builder()
                        .query(query).success(false)
                        .errorMessage("SearXNG HTTP " + response.statusCode())
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // 解析 JSON 响应
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            JsonNode resultsNode = root.path("results");
            if (!resultsNode.isArray()) {
                log.warn("WebSearchService: SearXNG response has no results array, query={}", query);
                return WebSearchResult.builder()
                        .query(query).success(false)
                        .totalResults(0)
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            int totalResults = resultsNode.size();
            Set<String> seenUrls = new HashSet<>();
            List<WebSearchFragment> fragments = new ArrayList<>();
            int rank = 1;

            for (JsonNode item : resultsNode) {
                if (fragments.size() >= maxResults) {
                    break;
                }

                String resultUrl = item.path("url").asText("");
                if (resultUrl.isEmpty() || seenUrls.contains(resultUrl)) {
                    continue;
                }
                seenUrls.add(resultUrl);

                String snippet = item.path("content").asText("");
                if (snippet.isBlank()) {
                    snippet = item.path("snippet").asText("");
                }
                // 裁剪摘要
                snippet = truncate(snippet, snippetMaxLength);

                String title = item.path("title").asText("");
                String publishedDate = item.path("publishedDate").asText(null);

                fragments.add(WebSearchFragment.builder()
                        .url(resultUrl)
                        .title(title)
                        .snippet(snippet)
                        .source(extractDomain(resultUrl))
                        .publishedAt(publishedDate)
                        .rank(rank++)
                        .adopted(false)
                        .discarded(false)
                        .confidence(0.0)
                        .verifyMethod("NONE")
                        .build());
            }

            log.info("WebSearchService: query={}, totalResults={}, parsed={}, duration={}ms",
                    query, totalResults, fragments.size(), System.currentTimeMillis() - startTime);

            return WebSearchResult.builder()
                    .query(query)
                    .fragments(fragments)
                    .success(true)
                    .totalResults(totalResults)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (Exception e) {
            log.warn("WebSearchService: search failed, query={}, error={}", query, e.getMessage());
            return WebSearchResult.builder()
                    .query(query).success(false)
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 检查 SearXNG 服务是否可用。
     *
     * @return 服务可用返回 true
     * @since 1.0.0
     */
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(searxngBaseUrl + "/healthz"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 URL 提取域名（去掉协议和路径）。
     */
    private String extractDomain(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return "";
            }
            // 去掉 www. 前缀，保留域名主体
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 截断文本到指定长度。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}