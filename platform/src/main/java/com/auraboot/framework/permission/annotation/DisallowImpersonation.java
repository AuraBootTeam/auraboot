package com.auraboot.framework.permission.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Blocks delegated customer sessions from security-sensitive account operations.
 * Apply at controller or method level; ordinary authenticated sessions are unaffected.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DisallowImpersonation {

    String value() default "This operation is unavailable while acting as another user";
}
