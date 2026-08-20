package com.gewu.application.sandbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.common.dto.sandbox.CreateSandboxCommand;
import com.gewu.common.dto.sandbox.ExecCommandRequest;
import com.gewu.common.dto.sandbox.ExecCommandResponse;
import com.gewu.common.dto.sandbox.ExecuteCodeRequest;
import com.gewu.common.dto.sandbox.RenewExpireRequest;
import com.gewu.common.dto.sandbox.SandboxDTO;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 沙箱服务 HTTP 客户端 - 统一封装对 gewu-sandbox (8082) 的 REST API 调用.
 * <p>替代直接注入 SandboxService/ContainerFileService 的进程内调用方式，
 * 实现模块间解耦。遵循项目既有 HttpClient 模式（ToolExecutionService/ProjectService）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper objectMapper;

    @Value("${gewu.sandbox.api.url:http://localhost:8082/api/v1/sandboxes}")
    private String baseUrl;

    /** 与沙箱服务 SandboxSecurityConfig 中的认证头保持一致 */
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    @Value("${gewu.sandbox.api.internal-key:}")
    private String internalApiKey;

    // ==================== 沙箱生命周期 ====================

    /** 创建沙箱 */
    public SandboxDTO createSandbox(CreateSandboxCommand command) {
        return postAndParse("", command, SandboxDTO.class);
    }

    /** 获取沙箱信息 */
    public SandboxDTO getSandbox(String sandboxId) {
        return getAndParse("/" + sandboxId, SandboxDTO.class);
    }

    /** 启动沙箱 */
    public SandboxDTO startSandbox(String sandboxId) {
        return postAndParse("/" + sandboxId + "/start", null, SandboxDTO.class);
    }

    /** 停止沙箱 */
    public SandboxDTO stopSandbox(String sandboxId) {
        return postAndParse("/" + sandboxId + "/stop", null, SandboxDTO.class);
    }

    /** 获取所有沙箱列表 */
    public List<SandboxDTO> listSandboxes() {
        try {
            HttpRequest request = authorized(HttpRequest.newBuilder())
                    .uri(URI.create(baseUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return parseResult(response, new TypeReference<List<SandboxDTO>>() {});
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "沙箱服务调用异常: " + e.getMessage());
        }
    }

    /** 销毁指定沙箱 */
    public void deleteSandbox(String sandboxId) {
        try {
            HttpRequest request = authorized(HttpRequest.newBuilder())
                    .uri(URI.create(baseUrl + "/" + sandboxId))
                    .timeout(Duration.ofSeconds(30))
                    .DELETE()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw BusinessException.of(ResultCode.SYSTEM_ERROR, "销毁沙箱失败: " + response.statusCode());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "销毁沙箱异常: " + e.getMessage());
        }
    }

    /** 沙箱续期（更新过期时间） */
    public SandboxDTO renewExpire(String sandboxId, RenewExpireRequest expireRequest) {
        return putAndParse("/" + sandboxId + "/expire", expireRequest, SandboxDTO.class);
    }

    /** 执行命令 */
    public ExecCommandResponse execCommand(String sandboxId, String command, Integer timeout) {
        ExecCommandRequest request = new ExecCommandRequest();
        request.setCommand(command);
        request.setTimeout(timeout);
        return postAndParse("/" + sandboxId + "/exec", request, ExecCommandResponse.class);
    }

    /** 执行代码（创建临时沙箱，执行后自动销毁） */
    public ExecCommandResponse executeCode(String language, String code, Integer timeout) {
        ExecuteCodeRequest request = new ExecuteCodeRequest();
        request.setLanguage(language);
        request.setCode(code);
        request.setTimeout(timeout);
        return postAndParse("/execute", request, ExecCommandResponse.class);
    }

    /** 销毁项目关联的沙箱 */
    public void destroyProjectSandboxes(String projectId) {
        try {
            HttpRequest request = authorized(HttpRequest.newBuilder())
                    .uri(URI.create(baseUrl + "/project/" + projectId))
                    .timeout(Duration.ofSeconds(10))
                    .DELETE()
                    .build();
            HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("销毁项目沙箱失败(非阻塞): projectId={}, error={}", projectId, e.getMessage());
        }
    }

    // ==================== 文件操作（Docker cp） ====================

    /** 写入文本文件到容器 */
    public void writeFile(String sandboxId, String path, String content) {
        postAndParse("/" + sandboxId + "/files/write",
                Map.of("path", path, "content", content), Void.class);
    }

    /** 读取容器内文件内容 */
    public String readFile(String sandboxId, String path) {
        try {
            HttpRequest request = authorized(HttpRequest.newBuilder())
                    .uri(URI.create(baseUrl + "/" + sandboxId + "/files/read?path=" + encode(path)))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw BusinessException.of(ResultCode.SYSTEM_ERROR,
                        "读取沙箱文件失败: " + response.statusCode());
            }
            return response.body();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "读取沙箱文件异常: " + e.getMessage());
        }
    }

    /** 上传文件到容器 */
    public void uploadFile(String sandboxId, String path, MultipartFile file) throws IOException {
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String CRLF = "\r\n";

        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append(CRLF);
        sb.append("Content-Disposition: form-data; name=\"path\"").append(CRLF).append(CRLF);
        sb.append(path).append(CRLF);
        sb.append("--").append(boundary).append(CRLF);
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
          .append(fileName).append("\"").append(CRLF);
        sb.append("Content-Type: ").append(file.getContentType() != null ? file.getContentType() : "application/octet-stream").append(CRLF).append(CRLF);

        byte[] header = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] footer = (CRLF + "--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8);
        byte[] fileBytes = file.getBytes();

        byte[] body = new byte[header.length + fileBytes.length + footer.length];
        System.arraycopy(header, 0, body, 0, header.length);
        System.arraycopy(fileBytes, 0, body, header.length, fileBytes.length);
        System.arraycopy(footer, 0, body, header.length + fileBytes.length, footer.length);

        try {
            HttpRequest request = authorized(HttpRequest.newBuilder())
                    .uri(URI.create(baseUrl + "/" + sandboxId + "/files/upload"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw BusinessException.of(ResultCode.SYSTEM_ERROR,
                        "上传沙箱文件失败: " + response.statusCode());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "上传沙箱文件异常: " + e.getMessage());
        }
    }

    /** 从容器下载文件 */
    public byte[] downloadFile(String sandboxId, String path) {
        try {
            HttpRequest request = authorized(HttpRequest.newBuilder())
                    .uri(URI.create(baseUrl + "/" + sandboxId + "/files/download?path=" + encode(path)))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw BusinessException.of(ResultCode.SYSTEM_ERROR,
                        "下载沙箱文件失败: " + response.statusCode());
            }
            return response.body();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "下载沙箱文件异常: " + e.getMessage());
        }
    }

    // ==================== 内部辅助 ====================

    /** 为请求构建器附加内部服务认证头，沙箱服务据此做服务间认证 */
    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        if (internalApiKey != null && !internalApiKey.isEmpty()) {
            return builder.header(INTERNAL_API_KEY_HEADER, internalApiKey);
        }
        return builder;
    }

    private <T> T postAndParse(String path, Object body, Class<T> type) {
        try {
            String json = body != null ? objectMapper.writeValueAsString(body) : "";
            HttpRequest request = authorized(HttpRequest.newBuilder())
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return parseResult(response, type);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "沙箱服务调用异常: " + e.getMessage());
        }
    }

    private <T> T getAndParse(String path, Class<T> type) {
        try {
            HttpRequest request = authorized(HttpRequest.newBuilder())
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return parseResult(response, type);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "沙箱服务调用异常: " + e.getMessage());
        }
    }

    private <T> T putAndParse(String path, Object body, Class<T> type) {
        try {
            String json = body != null ? objectMapper.writeValueAsString(body) : "";
            HttpRequest request = authorized(HttpRequest.newBuilder())
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return parseResult(response, type);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "沙箱服务调用异常: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T parseResult(HttpResponse<String> response, Class<T> type) {
        if (response.statusCode() != 200) {
            log.error("沙箱服务 HTTP 错误: status={}, body={}", response.statusCode(), response.body());
            throw BusinessException.of(ResultCode.SYSTEM_ERROR,
                    "沙箱服务返回 " + response.statusCode());
        }
        try {
            Map<String, Object> result = objectMapper.readValue(response.body(),
                    new TypeReference<Map<String, Object>>() {});
            Integer code = (Integer) result.get("code");
            if (code == null || code != 10000) {
                String msg = result.getOrDefault("message", "未知错误").toString();
                throw BusinessException.of(ResultCode.SYSTEM_ERROR, "沙箱服务: " + msg);
            }
            Object data = result.get("data");
            if (data == null || type == Void.class) return null;
            return objectMapper.convertValue(data, type);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "沙箱响应解析失败: " + e.getMessage());
        }
    }

    /** 支持泛型集合（如 List&lt;SandboxDTO&gt;）的响应解析 */
    private <T> T parseResult(HttpResponse<String> response, TypeReference<T> type) {
        if (response.statusCode() != 200) {
            log.error("沙箱服务 HTTP 错误: status={}, body={}", response.statusCode(), response.body());
            throw BusinessException.of(ResultCode.SYSTEM_ERROR,
                    "沙箱服务返回 " + response.statusCode());
        }
        try {
            Map<String, Object> result = objectMapper.readValue(response.body(),
                    new TypeReference<Map<String, Object>>() {});
            Integer code = (Integer) result.get("code");
            if (code == null || code != 10000) {
                String msg = result.getOrDefault("message", "未知错误").toString();
                throw BusinessException.of(ResultCode.SYSTEM_ERROR, "沙箱服务: " + msg);
            }
            Object data = result.get("data");
            if (data == null) return null;
            return objectMapper.convertValue(data, type);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "沙箱响应解析失败: " + e.getMessage());
        }
    }

    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
