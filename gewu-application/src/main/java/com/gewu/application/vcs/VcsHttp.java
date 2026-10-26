package com.gewu.application.vcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 托管平台 REST 访问工具（java.net.http + Jackson；非 2xx 抛业务异常，透出平台 message）。
 */
final class VcsHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper M = new ObjectMapper();

    private VcsHttp() {
    }

    static JsonNode get(String url, Map<String, String> headers) {
        return exchange(url, "GET", headers, null);
    }

    static JsonNode post(String url, Map<String, String> headers, Object body) {
        return exchange(url, "POST", headers, body);
    }

    /** 无 body POST（GitLab approve 等端点） */
    static JsonNode postEmpty(String url, Map<String, String> headers) {
        return exchangeBytes(url, headers, new byte[0], null);
    }

    /** multipart 文件上传（Release 附件 / 平台上传端点） */
    static JsonNode postMultipart(String url, Map<String, String> headers, String fileField, String fileName, byte[] data) {
        try {
            String boundary = "----gewu" + System.nanoTime();
            var out = new java.io.ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + fileName + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary);
            headers.forEach(b::header);
            b.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
            HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw BusinessException.of(ResultCode.SYSTEM_ERROR,
                        "平台上传错误(" + resp.statusCode() + "): " + extractMessage(resp.body()));
            }
            return M.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "托管平台上传失败: " + e.getMessage());
        }
    }

    /** 字节体请求（空 body POST 等；content-type 可空） */
    static JsonNode exchangeBytes(String url, Map<String, String> headers, byte[] bodyBytes, String contentType) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            headers.forEach(b::header);
            if (bodyBytes != null && bodyBytes.length > 0) {
                if (contentType != null) {
                    b.header("Content-Type", contentType);
                }
                b.POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
            } else {
                b.POST(HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw BusinessException.of(ResultCode.SYSTEM_ERROR,
                        "平台接口错误(" + resp.statusCode() + "): " + extractMessage(resp.body()));
            }
            return M.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "托管平台访问失败: " + e.getMessage());
        }
    }

    private static JsonNode exchange(String url, String method, Map<String, String> headers, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json");
            headers.forEach(b::header);
            if (body != null) {
                b.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)));
            } else {
                b.GET();
            }
            HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw BusinessException.of(ResultCode.SYSTEM_ERROR,
                        "平台接口错误(" + resp.statusCode() + "): " + extractMessage(resp.body()));
            }
            return M.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "托管平台访问失败: " + e.getMessage());
        }
    }

    /** 平台错误体兼容：{"message": ...}（GitHub/GitLab/Gitea）/ {"errorMessage": ...} 变体 / 纯文本 */
    private static String extractMessage(String body) {
        try {
            JsonNode n = M.readTree(body == null ? "" : body);
            if (n.has("message") && !n.get("message").isNull()) {
                return truncate(n.get("message").asText());
            }
            if (n.has("errorMessage")) {
                return truncate(n.get("errorMessage").asText());
            }
        } catch (Exception ignored) {
        }
        return truncate(body == null ? "" : body);
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
