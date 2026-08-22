package com.gewu.agent.engine.tool.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.AgentEngineException;
import com.gewu.agent.engine.spi.ToolConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SchemaValidator} JSON Schema 参数校验测试。
 */
@DisplayName("Schema 校验器")
class SchemaValidatorTest {

    private SchemaValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SchemaValidator(new ObjectMapper());
    }

    private static final String WEATHER_SCHEMA = """
            {"type":"object","required":["city"],
             "properties":{"city":{"type":"string"},"days":{"type":"integer"}}}
            """;

    @Test
    @DisplayName("符合 schema 的参数通过校验")
    void validArgumentsPass() {
        var result = validator.validate(WEATHER_SCHEMA, "{\"city\":\"北京\",\"days\":3}");
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("必填字段缺失报错")
    void missingRequiredField() {
        var result = validator.validate(WEATHER_SCHEMA, "{\"days\":3}");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("city") && e.contains("必填"));
    }

    @Test
    @DisplayName("字段类型不匹配报错（string 传数字）")
    void typeMismatch() {
        var result = validator.validate(WEATHER_SCHEMA, "{\"city\":123}");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("city") && e.contains("字符串"));
    }

    @Test
    @DisplayName("嵌套对象属性递归校验")
    void nestedPropertyValidated() {
        String schema = """
                {"type":"object","required":["user"],
                 "properties":{"user":{"type":"object","required":["name"],
                   "properties":{"name":{"type":"string"},"age":{"type":"integer"}}}}}
                """;
        var missing = validator.validate(schema, "{\"user\":{\"age\":20}}");
        assertThat(missing.valid()).isFalse();
        assertThat(missing.errors()).anyMatch(e -> e.contains("user.name"));

        var ok = validator.validate(schema, "{\"user\":{\"name\":\"张三\",\"age\":20}}");
        assertThat(ok.valid()).isTrue();
    }

    @Test
    @DisplayName("根类型非对象报错（数组/字符串/数字/布尔）")
    void rootTypeMismatch() {
        assertThat(validator.validate("{\"type\":\"object\"}", "[1,2]").valid()).isFalse();
        assertThat(validator.validate("{\"type\":\"array\"}", "\"not-array\"").valid()).isFalse();
        assertThat(validator.validate("{\"type\":\"string\"}", "42").valid()).isFalse();
        assertThat(validator.validate("{\"type\":\"boolean\"}", "1").valid()).isFalse();
    }

    @Test
    @DisplayName("参数非法 JSON 时返回解析失败而非抛异常")
    void invalidJsonArguments() {
        var result = validator.validate(WEATHER_SCHEMA, "not-json{{{");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("JSON 解析失败"));
    }

    @Test
    @DisplayName("schema 为空时跳过校验")
    void blankSchemaSkips() {
        assertThat(validator.validate("", "anything").valid()).isTrue();
        assertThat(validator.validate(null, "{}").valid()).isTrue();
    }

    @Test
    @DisplayName("SecurityCheck 路径：校验失败抛 SCHEMA_INVALID，无配置跳过")
    void checkPathThrowsOnInvalid() {
        ToolConfig config = ToolConfig.builder()
                .toolName("weather").toolType("http")
                .requestSchema(WEATHER_SCHEMA).build();
        assertThatThrownBy(() -> validator.check("weather", "{\"days\":1}", null, config))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("参数校验失败");
        // 无 schema 配置直接放行
        assertThatCode(() -> validator.check("weather", "{}", null, null))
                .doesNotThrowAnyException();
    }
}
