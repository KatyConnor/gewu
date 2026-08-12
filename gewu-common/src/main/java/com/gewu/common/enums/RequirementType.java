package com.gewu.common.enums;

/**
 * 需求类型枚举.
 */
public enum RequirementType {

    STORY("STORY", "用户故事", "📖"),
    TASK("TASK", "任务", "✅"),
    BUG("BUG", "缺陷", "🐛"),
    IMPROVEMENT("IMPROVEMENT", "改进", "⬆️"),
    FEATURE("FEATURE", "功能需求", "✨"),
    EPIC("EPIC", "史诗", "🏔️");

    private final String code;
    private final String displayName;
    private final String icon;

    RequirementType(String code, String displayName, String icon) {
        this.code = code;
        this.displayName = displayName;
        this.icon = icon;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public String getIcon() { return icon; }
}
