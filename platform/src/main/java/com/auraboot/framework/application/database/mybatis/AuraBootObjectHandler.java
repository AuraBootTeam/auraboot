package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * MyBatis-Plus auto-fill handler for common fields and env-layering metadata.
 *
 * <ul>
 *   <li>{@code createdAt} / {@code updatedAt}: standard timestamps.</li>
 *   <li>{@code envId} (env-layering PoC): for {@code @EnvScoped} entities, fills env_id from
 *       {@link MetaContext#getCurrentEnvironmentId()} or falls back to the tenant default env.
 *       Explicit non-null envId on the entity is honored (used by promotion cross-env writes).</li>
 * </ul>
 *
 * @since 5.1.0 (timestamps)
 * @since env-layering PoC (envId)
 */
@Slf4j
@Component
public class AuraBootObjectHandler implements MetaObjectHandler {

    private final EnvironmentService environmentService;

    @Autowired
    public AuraBootObjectHandler(@Lazy EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = Instant.now();
        this.strictInsertFill(metaObject, "createdAt", Instant.class, now);
        this.strictInsertFill(metaObject, "updatedAt", Instant.class, now);
        fillEnvIdIfApplicable(metaObject);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", Instant.class, Instant.now());
        // env_id is set on insert and not auto-updated.
    }

    private void fillEnvIdIfApplicable(MetaObject metaObject) {
        if (!metaObject.hasGetter("envId")) {
            return;
        }
        Object existing = metaObject.getValue("envId");
        if (existing != null) {
            return;  // explicit set wins (e.g. promotion cross-env writes)
        }
        Long envId = MetaContext.getCurrentEnvironmentId();
        if (envId == null) {
            Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
            if (tenantId == null) {
                log.debug("envId fill skipped: no MetaContext / no tenantId");
                return;
            }
            envId = environmentService.findOrCreateDefaultId(tenantId);
        }
        metaObject.setValue("envId", envId);
    }
}
