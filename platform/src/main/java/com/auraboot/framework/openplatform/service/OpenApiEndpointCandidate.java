package com.auraboot.framework.openplatform.service;

import java.lang.annotation.*;

/** Developer-reviewed adaptation of an internal route, never automatic Controller invocation. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OpenApiEndpointCandidate {
    String code();
    OpenApiEndpointKind adapter();
    String version();
    String name();
    String chineseName();
    String actionSourceKind() default "";
}
