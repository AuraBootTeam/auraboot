package com.auraboot.framework.permission.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a global control-plane operation requiring an authenticated platform administrator.
 * Unlike AuthenticatedAccess, this contract never bypasses role authorization.
 * When combined with RequirePermission, both requirements must pass.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePlatformAdmin {}
