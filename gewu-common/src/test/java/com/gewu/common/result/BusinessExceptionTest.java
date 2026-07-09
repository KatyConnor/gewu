package com.gewu.common.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTest {

    @Test
    @DisplayName("of(ResultCode) 设置正确的 code 和 message")
    void ofResultCodeSetsCorrectCodeAndMessage() {
        BusinessException ex = BusinessException.of(ResultCode.USER_NOT_FOUND);
        assertThat(ex.getCode()).isEqualTo(11001);
        assertThat(ex.getMessage()).isEqualTo("用户不存在");
    }

    @Test
    @DisplayName("of(ResultCode, String) 设置自定义 message")
    void ofResultCodeAndCustomMessageSetsCustomMessage() {
        BusinessException ex = BusinessException.of(ResultCode.PARAM_INVALID, "用户名不能为空");
        assertThat(ex.getCode()).isEqualTo(10002);
        assertThat(ex.getMessage()).isEqualTo("用户名不能为空");
    }

    @Test
    @DisplayName("getCode() 返回错误码")
    void getCodeReturnsTheCode() {
        BusinessException ex = BusinessException.of(ResultCode.TOKEN_EXPIRED);
        assertThat(ex.getCode()).isEqualTo(11006);
    }

    @Test
    @DisplayName("构造器 BusinessException(int, String) 设置 code 和 message")
    void constructorWithCodeAndStringSetsValues() {
        BusinessException ex = new BusinessException(50000, "自定义错误");
        assertThat(ex.getCode()).isEqualTo(50000);
        assertThat(ex.getMessage()).isEqualTo("自定义错误");
    }

    @Test
    @DisplayName("BusinessException 是 RuntimeException 子类")
    void businessExceptionIsRuntimeException() {
        BusinessException ex = BusinessException.of(ResultCode.SYSTEM_ERROR);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("构造器 BusinessException(ResultCode, Throwable) 携带原因异常")
    void constructorWithCauseCarriesCause() {
        Throwable cause = new RuntimeException("根本原因");
        BusinessException ex = new BusinessException(ResultCode.SYSTEM_ERROR, cause);
        assertThat(ex.getCode()).isEqualTo(10001);
        assertThat(ex.getCause()).isEqualTo(cause);
    }

    @Test
    @DisplayName("构造器 BusinessException(ResultCode) 设置正确的 message")
    void constructorWithResultCodeSetsMessage() {
        BusinessException ex = new BusinessException(ResultCode.FORBIDDEN);
        assertThat(ex.getMessage()).isEqualTo("无访问权限");
        assertThat(ex.getCode()).isEqualTo(10004);
    }
}
