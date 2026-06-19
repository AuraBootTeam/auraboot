package com.auraboot.framework.permission.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler (method or controller) as intentionally requiring authentication ONLY —
 * no specific {@link RequirePermission} is needed.
 *
 * <p>This is the explicit, reviewed counterpart to leaving an endpoint un-annotated. It exists for
 * the staged migration to a default-deny authorization model (see {@code PermissionInterceptor} and
 * {@code aura.security.authz.unannotated-mode}):
 * <ul>
 *   <li>In {@code shadow} mode, handlers marked with this annotation are NOT reported as
 *       "unannotated reached" — they are an acknowledged, self-scoped / authenticated-only surface.</li>
 *   <li>In {@code deny} mode, handlers marked with this annotation are still allowed; only truly
 *       un-annotated handlers (neither {@code @RequirePermission} nor {@code @AuthenticatedAccess})
 *       are denied.</li>
 * </ul>
 *
 * <p>Use it for endpoints that operate only on the caller's own data (preferences, sessions, own
 * notifications, own conversations) or pre-membership flows — anything where authentication is the
 * complete access-control story and no RBAC permission applies.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthenticatedAccess {

    /** Optional human note on why this endpoint needs only authentication (for audit/review). */
    String value() default "";
}
