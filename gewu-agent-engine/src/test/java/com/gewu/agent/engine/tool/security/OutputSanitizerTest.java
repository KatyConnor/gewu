package com.gewu.agent.engine.tool.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OutputSanitizer} PII 脱敏测试。
 */
@DisplayName("输出脱敏器")
class OutputSanitizerTest {

    private final OutputSanitizer sanitizer = new OutputSanitizer();

    @Test
    @DisplayName("手机号脱敏：保留前 3 后 2 位")
    void phoneMasked() {
        String result = sanitizer.checkOutput("联系我 13812345678 谢谢");
        assertThat(result).isEqualTo("联系我 138********78 谢谢");
    }

    @Test
    @DisplayName("邮箱脱敏：保留首字符与域名")
    void emailMasked() {
        String result = sanitizer.checkOutput("发送到 alice@example.com 即可");
        assertThat(result).isEqualTo("发送到 a***@example.com 即可");
    }

    @Test
    @DisplayName("身份证号脱敏：保留前 3 后 4 位")
    void idCardMasked() {
        String result = sanitizer.checkOutput("证件号 110101199003077758 备案");
        assertThat(result).isEqualTo("证件号 110***********7758 备案");
    }

    @Test
    @DisplayName("银行卡号脱敏：保留前 4 后 4 位")
    void bankCardMasked() {
        String result = sanitizer.checkOutput("卡号 6222021234561234 转账");
        assertThat(result).isEqualTo("卡号 6222****1234 转账");
    }

    @Test
    @DisplayName("多种 PII 混合并全部脱敏")
    void multiplePiiMasked() {
        String result = sanitizer.checkOutput("用户 alice@example.com 电话 13812345678");
        assertThat(result)
                .contains("a***@example.com")
                .contains("138********78")
                .doesNotContain("alice@")
                .doesNotContain("13812345678");
    }

    @Test
    @DisplayName("无 PII 内容与空值原样返回")
    void normalContentUntouched() {
        assertThat(sanitizer.checkOutput("今天天气不错")).isEqualTo("今天天气不错");
        assertThat(sanitizer.checkOutput(null)).isNull();
        assertThat(sanitizer.checkOutput("  ")).isEqualTo("  ");
    }

    @Test
    @DisplayName("数字前后有更多数字时不误伤（边界断言）")
    void longerDigitSequenceTreatedAsCardOrId() {
        // 11 位手机号嵌在 13 位数字中间不匹配手机号模式
        String result = sanitizer.checkOutput("编号 9138123456789 完毕");
        assertThat(result).doesNotContain("138********78");
    }
}
