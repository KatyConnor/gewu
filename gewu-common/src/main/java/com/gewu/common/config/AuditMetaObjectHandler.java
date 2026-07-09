package com.gewu.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.gewu.common.context.UserContext;
import com.gewu.common.ulid.Ulid;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        fillIfNull(metaObject, "id", String.class, Ulid.next());
        long now = Instant.now().toEpochMilli();
        fillIfNull(metaObject, "createdAt", Long.class, now);
        fillIfNull(metaObject, "updatedAt", Long.class, now);
        fillIfNull(metaObject, "createdBy", String.class, getCurrentUser());
        fillIfNull(metaObject, "updatedBy", String.class, getCurrentUser());
        fillIfNull(metaObject, "deleted", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasGetter("updatedAt")) {
            metaObject.setValue("updatedAt", Instant.now().toEpochMilli());
        }
        if (metaObject.hasGetter("updatedBy")) {
            metaObject.setValue("updatedBy", getCurrentUser());
        }
    }

    private void fillIfNull(MetaObject metaObject, String fieldName, Class<?> type, Object value) {
        if (!metaObject.hasGetter(fieldName)) return;
        Object current = metaObject.getValue(fieldName);
        if (current == null || current.toString().isEmpty()) {
            metaObject.setValue(fieldName, value);
        }
    }

    private String getCurrentUser() {
        UserContext ctx = UserContext.get();
        return ctx != null ? ctx.getUserId() : "system";
    }
}