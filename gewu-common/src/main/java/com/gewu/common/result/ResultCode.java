package com.gewu.common.result;

/**
 * 统一错误码枚举 — 5 位数字编码.
 *
 * <p>编码规则: [域名2位][模块2位][序号1位]
 * <ul>
 *   <li>10xxx — 通用错误</li>
 *   <li>11xxx — 用户权限域</li>
 *   <li>12xxx — 项目管理域</li>
 *   <li>13xxx — 会话消息域</li>
 *   <li>14xxx — Agent 系统域</li>
 *   <li>15xxx — 工作流域</li>
 *   <li>16xxx — 沙箱域</li>
 *   <li>17xxx — 网关域</li>
 * </ul>
 */
public enum ResultCode {

    SUCCESS(10000, "操作成功"),
    SYSTEM_ERROR(10001, "系统内部错误"),
    PARAM_INVALID(10002, "参数校验失败"),
    UNAUTHORIZED(10003, "未认证"),
    FORBIDDEN(10004, "无访问权限"),
    NOT_FOUND(10005, "资源不存在"),
    METHOD_NOT_ALLOWED(10006, "请求方法不支持"),
    RATE_LIMITED(10007, "请求过于频繁"),
    SERVICE_UNAVAILABLE(10008, "服务暂不可用"),

    USER_NOT_FOUND(11001, "用户不存在"),
    USER_ALREADY_EXISTS(11002, "用户已存在"),
    PASSWORD_INCORRECT(11003, "密码错误"),
    USER_DISABLED(11004, "用户已禁用"),
    USER_LOCKED(11005, "用户已锁定"),
    TOKEN_EXPIRED(11006, "令牌已过期"),
    TOKEN_INVALID(11007, "令牌无效"),
    REFRESH_TOKEN_EXPIRED(11008, "刷新令牌已过期"),
    ROLE_NOT_FOUND(11009, "角色不存在"),
    PERMISSION_DENIED(11010, "权限不足"),
    PASSWORD_POLICY_VIOLATION(11011, "密码不符合安全策略"),
    PASSWORD_TOO_COMMON(11012, "密码过于简单"),
    IP_BLOCKED(11013, "IP已被封禁"),

    PROJECT_NOT_FOUND(12001, "项目不存在"),
    PROJECT_ALREADY_EXISTS(12002, "项目已存在"),
    PROJECT_MEMBER_LIMIT(12003, "项目成员数量超限"),

    SESSION_NOT_FOUND(13001, "会话不存在"),
    MESSAGE_NOT_FOUND(13002, "消息不存在"),
    MESSAGE_TOO_LONG(13003, "消息内容过长"),

    AGENT_NOT_FOUND(14001, "Agent 不存在"),
    AGENT_EXECUTION_FAILED(14002, "Agent 执行失败"),
    AGENT_TIMEOUT(14003, "Agent 执行超时"),
    TOOL_NOT_FOUND(14004, "工具不存在"),

    WORKFLOW_NOT_FOUND(15001, "工作流不存在"),
    WORKFLOW_INVALID_STATE(15002, "工作流状态不允许此操作"),
    WORKFLOW_INSTANCE_NOT_FOUND(15003, "工作流实例不存在"),
    WORKFLOW_NODE_NOT_FOUND(15004, "工作流节点不存在"),

    SANDBOX_NOT_FOUND(16001, "沙箱不存在"),
    SANDBOX_RUNTIME_ERROR(16002, "沙箱运行时错误"),
    SANDBOX_TIMEOUT(16003, "沙箱执行超时"),
    SANDBOX_RESOURCE_LIMIT(16004, "沙箱资源超限"),

    GATEWAY_ROUTE_NOT_FOUND(17001, "网关路由不存在"),
    GATEWAY_CIRCUIT_OPEN(17002, "服务熔断中"),
    GATEWAY_TLS_ERROR(17003, "TLS 握手失败");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}