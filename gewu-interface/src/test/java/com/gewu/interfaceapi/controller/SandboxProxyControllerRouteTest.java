package com.gewu.interfaceapi.controller;

import com.gewu.application.sandbox.SandboxClient;
import com.gewu.common.dto.sandbox.SandboxDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 沙箱代理路由回归测试（P0-2）：
 * 按沙箱 id 的 5 个代理端点曾因路径字面量损坏（"/glm-5.3_common"）全部失效，
 * 恢复为 /{id} 后本测试锁定路由语义，防止再次被批量替换误伤。
 */
class SandboxProxyControllerRouteTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SandboxClient sandboxClient = mock(SandboxClient.class);
        when(sandboxClient.getSandbox("sb-1")).thenReturn(new SandboxDTO());
        when(sandboxClient.startSandbox("sb-1")).thenReturn(new SandboxDTO());
        when(sandboxClient.stopSandbox("sb-1")).thenReturn(new SandboxDTO());
        when(sandboxClient.renewExpire(eq("sb-1"), any())).thenReturn(new SandboxDTO());
        mockMvc = MockMvcBuilders.standaloneSetup(new SandboxProxyController(sandboxClient)).build();
    }

    @Test
    @DisplayName("GET /{id} 命中详情端点")
    void getRoute() throws Exception {
        mockMvc.perform(get("/api/v1/sandboxes/sb-1")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /{id}/start 命中启动端点")
    void startRoute() throws Exception {
        mockMvc.perform(post("/api/v1/sandboxes/sb-1/start")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /{id}/stop 命中停止端点")
    void stopRoute() throws Exception {
        mockMvc.perform(post("/api/v1/sandboxes/sb-1/stop")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE /{id} 命中销毁端点")
    void deleteRoute() throws Exception {
        mockMvc.perform(delete("/api/v1/sandboxes/sb-1")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /{id}/expire 命中续期端点")
    void renewRoute() throws Exception {
        mockMvc.perform(put("/api/v1/sandboxes/sb-1/expire")
                        .contentType("application/json")
                        .content("{\"ttlDays\": 1}"))
                .andExpect(status().isOk());
    }
}
