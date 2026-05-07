package com.auraboot.framework.environment.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an entity as environment-scoped: the persistence layer must filter and stamp rows by
 * {@code env_id} from {@link com.auraboot.framework.application.tenant.MetaContext}.
 *
 * <p>Default behavior (entities without this annotation) is unchanged — only tenant_id is
 * applied. Adding @EnvScoped is the explicit opt-in for cross-environment isolation. Whitelist,
 * not blacklist — body-level identity tables (ab_user / ab_role) and runtime business tables
 * (mt_*) intentionally stay un-annotated.
 *
 * <p>Annotated entities MUST:
 * <ul>
 *   <li>have an {@code env_id BIGINT NOT NULL} column with FK to {@code ab_environment(id)}</li>
 *   <li>have a corresponding {@code envId} field (Long) on the entity class</li>
 * </ul>
 *
 * <p>Cross-environment reads (e.g. promotion source-vs-target diff) bypass the filter via
 * {@link com.auraboot.framework.application.tenant.MetaContext#runWithoutEnvFilter(java.util.function.Supplier)}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EnvScoped {
}
