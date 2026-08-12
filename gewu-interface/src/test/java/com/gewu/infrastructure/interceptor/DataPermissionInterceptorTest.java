package com.gewu.infrastructure.interceptor;

import com.gewu.common.context.UserContext;
import net.sf.jsqlparser.expression.Expression;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据权限拦截器 SQL 改写测试.
 * <p>验证各 data_scope 场景下的 SQL 条件追加逻辑.
 */
class DataPermissionInterceptorTest {

    private final DataPermissionInnerInterceptor interceptor = new DataPermissionInnerInterceptor();

    private static final String BASE_SQL = "SELECT * FROM agent WHERE deleted = 0 ORDER BY created_at DESC LIMIT ?, ?";
    private static final String NO_WHERE_SQL = "SELECT * FROM agent ORDER BY created_at DESC LIMIT ?, ?";

    @AfterEach
    void cleanup() {
        UserContext.clear();
        DataPermissionContext.clear();
    }

    @Test
    @DisplayName("data_scope=1 全部: 不追加条件")
    void scope1_noCondition() {
        setUserContext(1, "userA", "org01");
        Expression condition = interceptor.buildCondition("created_by", 1, "userA", "org01");
        assertNull(condition, "data_scope=1 不应生成任何条件");
    }

    @Test
    @DisplayName("data_scope=4 本人: 追加 created_by = 'userId'")
    void scope4_selfOnly() {
        setUserContext(4, "userA", "org01");
        Expression condition = interceptor.buildCondition("created_by", 4, "userA", "org01");
        assertNotNull(condition);

        String rewritten = interceptor.rewriteSql(BASE_SQL, condition);
        assertNotNull(rewritten);
        assertTrue(rewritten.contains("created_by = 'userA'"), "应包含 created_by = 'userA'");
        assertTrue(rewritten.contains("deleted = 0"), "原 WHERE 条件应保留");
    }

    @Test
    @DisplayName("data_scope=2 本部门: 追加 IN 子查询")
    void scope2_department() {
        setUserContext(2, "userA", "org01");
        Expression condition = interceptor.buildCondition("created_by", 2, "userA", "org01");
        assertNotNull(condition);

        String rewritten = interceptor.rewriteSql(BASE_SQL, condition);
        assertNotNull(rewritten);
        assertTrue(rewritten.contains("user_account"), "应包含 user_account 子查询");
        assertTrue(rewritten.contains("org_id = 'org01'"), "应包含 org_id = 'org01'");
        assertTrue(rewritten.contains("IN"), "应使用 IN 表达式");
    }

    @Test
    @DisplayName("data_scope=3 本部门及以下: 追加含子部门的 IN 子查询")
    void scope3_departmentAndChildren() {
        setUserContext(3, "userA", "org01");
        Expression condition = interceptor.buildCondition("created_by", 3, "userA", "org01");
        assertNotNull(condition);

        String rewritten = interceptor.rewriteSql(BASE_SQL, condition);
        assertNotNull(rewritten);
        assertTrue(rewritten.contains("organization"), "应包含 organization 子查询");
        assertTrue(rewritten.contains("parent_id = 'org01'"), "应包含 parent_id = 'org01'");
        assertTrue(rewritten.contains("OR"), "应包含 OR 连接本部门与子部门");
    }

    @Test
    @DisplayName("data_scope=2 但 orgId 为 null: 降级为本人")
    void scope2_nullOrg_fallbackToSelf() {
        setUserContext(2, "userA", null);
        Expression condition = interceptor.buildCondition("created_by", 2, "userA", null);
        assertNotNull(condition);

        String rewritten = interceptor.rewriteSql(BASE_SQL, condition);
        assertNotNull(rewritten);
        assertTrue(rewritten.contains("created_by = 'userA'"), "orgId 为 null 时应降级为本人");
    }

    @Test
    @DisplayName("无 WHERE 的 SQL: 追加 WHERE 条件")
    void noWhere_addsWhere() {
        setUserContext(4, "userA", "org01");
        Expression condition = interceptor.buildCondition("created_by", 4, "userA", "org01");

        String rewritten = interceptor.rewriteSql(NO_WHERE_SQL, condition);
        assertNotNull(rewritten);
        assertTrue(rewritten.contains("WHERE"), "应添加 WHERE 子句");
        assertTrue(rewritten.contains("created_by = 'userA'"));
    }

    @Test
    @DisplayName("带表别名的列: a.created_by")
    void aliasColumn() {
        setUserContext(4, "userA", "org01");
        Expression condition = interceptor.buildCondition("a.created_by", 4, "userA", "org01");

        String rewritten = interceptor.rewriteSql(BASE_SQL, condition);
        assertNotNull(rewritten);
        assertTrue(rewritten.contains("a.created_by = 'userA'"), "应使用带别名的列名");
    }

    @Test
    @DisplayName("ADMIN 全部: UserContext dataScope=1 不改写 SQL")
    void adminNoRewrite() {
        setUserContext(1, "admin", "org01");

        // 模拟 beforeQuery 的判断逻辑
        UserContext user = UserContext.get();
        Integer dataScope = user.getDataScope();
        assertNull(interceptor.buildCondition("created_by", dataScope, user.getUserId(), user.getOrgId()),
                "ADMIN (data_scope=1) 不应生成条件");
    }

    private void setUserContext(int dataScope, String userId, String orgId) {
        UserContext.set(UserContext.builder()
                .userId(userId)
                .username(userId)
                .dataScope(dataScope)
                .orgId(orgId)
                .build());
    }
}
