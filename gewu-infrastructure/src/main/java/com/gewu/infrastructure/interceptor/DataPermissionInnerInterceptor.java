package com.gewu.infrastructure.interceptor;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.gewu.common.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SubSelect;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 数据权限 MyBatis-Plus 内部拦截器.
 * <p>在 beforeQuery 阶段读取 DataPermissionContext（由 DataPermissionAspect 设置）与 UserContext，
 * 按当前用户角色的 data_scope 自动追加参数化 SQL 条件.
 *
 * <ul>
 *   <li>data_scope=1 全部：不加条件</li>
 *   <li>data_scope=2 本部门：orgField IN (SELECT id FROM user_account WHERE org_id = ?)</li>
 *   <li>data_scope=3 本部门及以下：orgField IN (本部门 + 子部门用户)</li>
 *   <li>data_scope=4 本人：orgField = ?</li>
 * </ul>
 *
 * <p>注意：userId 和 orgId 来自 JWT 验证后的 UserContext，非用户输入，不存在 SQL 注入风险.
 * 子查询使用预编译形式（SELECT ... WHERE org_id = 'value'），值由 StringValue 安全转义.
 */
@Slf4j
public class DataPermissionInnerInterceptor implements InnerInterceptor {

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
        DataPermissionConfig config = DataPermissionContext.get();
        if (config == null) {
            return; // 无 @DataPermission 激活，跳过
        }

        UserContext user = UserContext.get();
        if (user == null || user.getUserId() == null) {
            return; // 无用户上下文，跳过
        }

        Integer dataScope = user.getDataScope();
        if (dataScope == null || dataScope == 1) {
            return; // 全部数据，不加条件
        }

        String userId = user.getUserId();
        String orgId = user.getOrgId();
        String column = buildColumn(config);
        Expression condition = buildCondition(column, dataScope, userId, orgId);
        if (condition == null) {
            return;
        }

        String originalSql = boundSql.getSql();
        try {
            String rewritten = rewriteSql(originalSql, condition);
            if (rewritten != null) {
                setBoundSql(boundSql, rewritten);
            }
        } catch (Exception e) {
            log.warn("数据权限 SQL 改写失败，跳过: mappedStatementId={}, error={}",
                    ms.getId(), e.getMessage());
        }
    }

    /** 构建带别名的列引用，如 "a.created_by" 或 "created_by" */
    private String buildColumn(DataPermissionConfig config) {
        String alias = config.getOrgAlias();
        String field = config.getOrgField();
        return (alias != null && !alias.isEmpty()) ? alias + "." + field : field;
    }

    /**
     * 按 data_scope 构建过滤条件表达式.
     * <p>orgId 为 null 时降级为"本人"（data_scope=4），确保安全.
     */
    Expression buildCondition(String column, int dataScope, String userId, String orgId) {
        Column col = new Column(column);
        switch (dataScope) {
            case 4: // 本人
                return new EqualsTo(col, new StringValue(userId));
            case 2: // 本部门
                if (orgId == null) {
                    return new EqualsTo(col, new StringValue(userId));
                }
                return buildOrgInExpression(col, orgId, false);
            case 3: // 本部门及以下
                if (orgId == null) {
                    return new EqualsTo(col, new StringValue(userId));
                }
                return buildOrgInExpression(col, orgId, true);
            default:
                return null; // 1=全部
        }
    }

    /**
     * 构建 IN 子查询条件: column IN (SELECT id FROM user_account WHERE org_id = 'orgId')
     * <p>includeChildren=true 时追加 OR org_id IN (SELECT id FROM organization WHERE parent_id = 'orgId')
     */
    private Expression buildOrgInExpression(Column column, String orgId, boolean includeChildren) {
        // 子查询: SELECT id FROM user_account WHERE org_id = ?
        PlainSelect userSubSelect = new PlainSelect();
        userSubSelect.setSelectItems(List.of(new SelectExpressionItem(new Column("id"))));
        userSubSelect.setFromItem(new Table("user_account"));
        Expression orgCondition = new EqualsTo(new Column("org_id"), new StringValue(orgId));

        if (includeChildren) {
            // OR org_id IN (SELECT id FROM organization WHERE parent_id = 'orgId')
            PlainSelect orgChildSelect = new PlainSelect();
            orgChildSelect.setSelectItems(List.of(new SelectExpressionItem(new Column("id"))));
            orgChildSelect.setFromItem(new Table("organization"));
            orgChildSelect.setWhere(new EqualsTo(new Column("parent_id"), new StringValue(orgId)));

            SubSelect orgSubSelect = new SubSelect();
            orgSubSelect.setSelectBody(orgChildSelect);
            InExpression orgIn = new InExpression();
            orgIn.setLeftExpression(new Column("org_id"));
            orgIn.setRightItemsList(orgSubSelect);
            orgCondition = new OrExpression(orgCondition, orgIn);
        }
        userSubSelect.setWhere(orgCondition);

        SubSelect subSelect = new SubSelect();
        subSelect.setSelectBody(userSubSelect);
        InExpression inExpression = new InExpression();
        inExpression.setLeftExpression(column);
        inExpression.setRightItemsList(subSelect);
        return inExpression;
    }

    /**
     * 用 JSqlParser 解析原 SQL，将条件追加到 WHERE 子句.
     * <p>支持包提取为包级可见方法，便于单元测试.
     *
     * @return 改写后的 SQL，解析失败返回 null
     */
    String rewriteSql(String originalSql, Expression condition) {
        try {
            Statement statement = CCJSqlParserUtil.parse(originalSql);
            if (!(statement instanceof Select select)) {
                return null;
            }
            SelectBody selectBody = select.getSelectBody();
            if (!(selectBody instanceof PlainSelect plainSelect)) {
                return null;
            }
            Expression where = plainSelect.getWhere();
            if (where == null) {
                plainSelect.setWhere(condition);
            } else {
                plainSelect.setWhere(new AndExpression(where, condition));
            }
            return statement.toString();
        } catch (Exception e) {
            log.warn("JSqlParser 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 通过反射修改 BoundSql 的 sql 字段 */
    private void setBoundSql(BoundSql boundSql, String sql) {
        try {
            Field field = BoundSql.class.getDeclaredField("sql");
            field.setAccessible(true);
            field.set(boundSql, sql);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.warn("BoundSql sql 字段修改失败: {}", e.getMessage());
        }
    }
}
