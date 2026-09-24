package com.gewu.agent.engine.spi;

/**
 * 模型上下文窗口提供者 SPI：返回模型的上下文输入窗口与最大输出（tokens）。
 * <p>数据源：model_config.context_window_input / context_window_output（管理员配置）。
 * 返回 null 表示未知——引擎跳过上下文压缩与输出上限校验（行为与旧版一致）。
 *
 * @since 1.0.0
 */
public interface ModelContextProvider {

    /** 上下文输入窗口（tokens），未知返回 null。 */
    Integer contextWindowInput(String modelId);

    /** 最大输出 tokens，未知返回 null。 */
    Integer contextWindowOutput(String modelId);
}
