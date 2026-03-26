package com.auraboot.framework.entitlement.annotation;

import java.lang.annotation.*;

/**
 * Declares that the annotated controller class or handler method requires a specific
 * feature flag to be enabled for the current tenant's plugin entitlement.
 *
 * <p>The feature key format is {@code pluginId.featureCode}, e.g. {@code crm.ai_scoring}.
 * The plugin ID prefix is used to resolve the parent plugin entitlement automatically.</p>
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
public @interface RequireFeature {
    /** The feature key (e.g. {@code crm.ai_scoring}) that must be active. */
    String value();
}
