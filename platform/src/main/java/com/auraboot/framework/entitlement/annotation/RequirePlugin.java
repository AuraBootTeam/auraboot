package com.auraboot.framework.entitlement.annotation;

import java.lang.annotation.*;

/**
 * Declares that the annotated controller class or handler method requires an active
 * plugin entitlement. The {@link #value()} must match a registered plugin ID.
 *
 * <p>Enforcement is handled by {@code EntitlementInterceptor} when
 * {@code auraboot.entitlement.enabled=true}.</p>
 *
 * @author AuraBoot Team
 * @since 3.0.0
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePlugin {
    /** The plugin ID that must be active for the current tenant. */
    String value();
}
