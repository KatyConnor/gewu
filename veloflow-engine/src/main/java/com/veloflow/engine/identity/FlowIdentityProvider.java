package com.veloflow.engine.identity;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 身份 SPI——引擎不依赖宿主登录态，用户身份经宿主实现注入。
 * <p>宿主集成示例（桥接自有登录态）：
 * <pre>{@code
 * @Component
 * public class MyIdentityProvider implements FlowIdentityProvider {
 *     public String currentUserId() { return SecurityUtils.userId(); }
 *     ...
 * }
 * }</pre>
 * 未提供实现时使用 {@link DefaultFlowIdentityProvider}（system 透传）。
 */
public interface FlowIdentityProvider {

    /** 当前用户 ID；无登录态返回 null（引擎按 system 处理） */
    String currentUserId();

    /** 当前用户名 */
    String currentUsername();

    /** 批量查询用户显示名（待办/时间线展示用），缺失用户可不在结果中 */
    Map<String, String> batchUserNames(Set<String> userIds);

    /** 当前用户的角色编码集（权限/审批人圈定用），默认空 */
    default List<String> currentRoles() {
        return List.of();
    }
}
