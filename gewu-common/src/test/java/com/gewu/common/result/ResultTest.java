package com.gewu.common.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {

    @Test
    @DisplayName("success() 返回 code=10000")
    void successReturnsCode10000() {
        Result<Void> result = Result.success();
        assertThat(result.getCode()).isEqualTo(10000);
        assertThat(result.getMessage()).isEqualTo("操作成功");
    }

    @Test
    @DisplayName("success(data) 返回传入的数据")
    void successWithDataReturnsData() {
        Result<String> result = Result.success("hello");
        assertThat(result.getCode()).isEqualTo(10000);
        assertThat(result.getData()).isEqualTo("hello");
    }

    @Test
    @DisplayName("success(data, message) 返回自定义消息")
    void successWithDataAndMessageReturnsCustomMessage() {
        Result<String> result = Result.success("data", "自定义成功");
        assertThat(result.getData()).isEqualTo("data");
        assertThat(result.getMessage()).isEqualTo("自定义成功");
    }

    @Test
    @DisplayName("fail(ResultCode) 返回正确的 code 和 message")
    void failWithResultCodeReturnsCorrectCodeAndMessage() {
        Result<Void> result = Result.fail(ResultCode.USER_NOT_FOUND);
        assertThat(result.getCode()).isEqualTo(11001);
        assertThat(result.getMessage()).isEqualTo("用户不存在");
    }

    @Test
    @DisplayName("fail(ResultCode, message) 返回自定义消息")
    void failWithResultCodeAndCustomMessageReturnsCustomMessage() {
        Result<Void> result = Result.fail(ResultCode.PARAM_INVALID, "字段不能为空");
        assertThat(result.getCode()).isEqualTo(10002);
        assertThat(result.getMessage()).isEqualTo("字段不能为空");
    }

    @Test
    @DisplayName("fail(int, message) 返回自定义 code 和 message")
    void failWithCodeAndMessageReturnsCustomValues() {
        Result<Void> result = Result.fail(99999, "自定义错误");
        assertThat(result.getCode()).isEqualTo(99999);
        assertThat(result.getMessage()).isEqualTo("自定义错误");
    }

    @Test
    @DisplayName("isSuccess() 对成功结果返回 true")
    void isSuccessReturnsTrueForSuccessResult() {
        Result<String> result = Result.success("data");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("isSuccess() 对失败结果返回 false")
    void isSuccessReturnsFalseForFailResult() {
        Result<Void> result = Result.fail(ResultCode.SYSTEM_ERROR);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("emptyList() 返回空列表和成功状态")
    void emptyListReturnsEmptyListWithSuccessStatus() {
        Result<List<String>> result = Result.emptyList();
        assertThat(result.getCode()).isEqualTo(10000);
        assertThat(result.getData()).isEmpty();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("timestamp 在创建时被设置")
    void timestampIsSetOnCreation() {
        long before = System.currentTimeMillis();
        Result<Void> result = Result.success();
        long after = System.currentTimeMillis();
        assertThat(result.getTimestamp()).isBetween(before, after);
    }
}
