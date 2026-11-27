package com.auraboot.framework.permission.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint whose domain-level authorization is delegated to the
 * shared application service layer (the domain-policy / published-domain
 * command paths), which authorizes both HTTP and non-HTTP callers uniformly.
 * <p>
 * The permission interceptor uses this marker instead of referencing the
 * controller classes, so no non-controller layer depends on the controller
 * layer (ArchUnit rule 1). Behavior is identical to the previous bean-type
 * comparisons this replaces.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainAuthorizationDelegated {
}
