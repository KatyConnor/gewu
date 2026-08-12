package com.gewu.interfaceconfig.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XSS 请求包装器 — 对参数名、参数值、请求头和请求体进行 HTML 实体转义.
 *
 * <p>CR-019: 增强 JSON 内容防护，对 JSON 字符串值进行转义。
 */
public class XssRequestWrapper extends HttpServletRequestWrapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private byte[] cachedBody;

    public XssRequestWrapper(HttpServletRequest request) {
        super(request);
    }

    @Override
    public String getParameter(String name) {
        String value = super.getParameter(xssEscape(name));
        return value != null ? xssEscape(value) : null;
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = super.getParameterValues(xssEscape(name));
        if (values == null) return null;
        String[] escaped = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            escaped[i] = xssEscape(values[i]);
        }
        return escaped;
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> original = super.getParameterMap();
        Map<String, String[]> result = new LinkedHashMap<>(original.size());
        for (Map.Entry<String, String[]> entry : original.entrySet()) {
            String key = xssEscape(entry.getKey());
            String[] values = entry.getValue();
            String[] escaped = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                escaped[i] = xssEscape(values[i]);
            }
            result.put(key, escaped);
        }
        return result;
    }

    @Override
    public String getHeader(String name) {
        String value = super.getHeader(name);
        return value != null ? xssEscape(value) : null;
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        Enumeration<String> headers = super.getHeaders(name);
        return new Enumeration<>() {
            @Override
            public boolean hasMoreElements() {
                return headers.hasMoreElements();
            }

            @Override
            public String nextElement() {
                return xssEscape(headers.nextElement());
            }
        };
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (cachedBody == null) {
            cachedBody = readBody();
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
            }

            @Override
            public int read() {
                return bais.read();
            }
        };
    }

    private byte[] readBody() throws IOException {
        byte[] raw = super.getInputStream().readAllBytes();
        String contentType = getContentType();
        if (contentType != null && contentType.toLowerCase().contains("application/json")) {
            // CR-019: 对 JSON 内容进行 XSS 防护
            return escapeJsonBody(raw);
        }
        String charset = getCharacterEncoding() != null ? getCharacterEncoding() : "UTF-8";
        String body = new String(raw, charset);
        return xssEscape(body).getBytes(charset);
    }

    /**
     * 对 JSON 内容进行 XSS 转义 — 仅转义字符串值，保持 JSON 结构。
     */
    private byte[] escapeJsonBody(byte[] raw) throws IOException {
        try {
            JsonNode rootNode = OBJECT_MAPPER.readTree(raw);
            JsonNode escapedNode = escapeJsonNode(rootNode);
            String charset = getCharacterEncoding() != null ? getCharacterEncoding() : "UTF-8";
            return OBJECT_MAPPER.writeValueAsBytes(escapedNode);
        } catch (Exception e) {
            // 如果 JSON 解析失败，返回原始内容（避免破坏请求）
            return raw;
        }
    }

    private JsonNode escapeJsonNode(JsonNode node) {
        if (node.isTextual()) {
            return new TextNode(xssEscape(node.asText()));
        } else if (node.isArray()) {
            ArrayNode arrayNode = OBJECT_MAPPER.createArrayNode();
            for (JsonNode child : node) {
                arrayNode.add(escapeJsonNode(child));
            }
            return arrayNode;
        } else if (node.isObject()) {
            ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                objectNode.set(xssEscape(field.getKey()), escapeJsonNode(field.getValue()));
            }
            return objectNode;
        }
        return node;
    }

    private static String xssEscape(String value) {
        if (value == null) return null;
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }
}