package com.auraboot.framework.meta.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as idempotent: duplicate requests with the same key
 * will return the cached response instead of re-executing.
 *
 * <p>Key resolution order:
 * <ol>
 *   <li>HTTP header {@code X-Idempotent-Key} (primary)</li>
 *   <li>SpEL expression from {@link #keyExpression()} (secondary)</li>
 *   <li>Request body SHA-256 hash (fallback, if {@link #includeBodyHash()} is true)</li>
 * </ol>
 *
 * <p>Concurrency is handled by PostgreSQL unique constraint on (idempotent_key, tenant_id).
 * No distributed lock is required.
 *
 * <p>Usage example:
 * <pre>
 * {@code @Idempotent(ttl = 3600)}
 * {@code @PostMapping("/execute/{commandCode}")}
 * public ApiResponse<CommandExecuteResult> execute(...) { ... }
 * </pre>
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * Time-to-live in seconds for the idempotent key (default 24 hours).
     * After expiry, the key is eligible for cleanup and the same key can be reused.
     */
    long ttl() default 86400;

    /**
     * SpEL expression for extracting the idempotent key from method arguments.
     * If empty, falls back to X-Idempotent-Key header.
     *
     * <p>Available variables in SpEL context:
     * <ul>
     *   <li>{@code #args} - method arguments array</li>
     *   <li>{@code #p0}, {@code #p1}, etc. - positional parameters</li>
     *   <li>{@code #commandCode} - first String parameter named commandCode</li>
     *   <li>{@code #request} - first parameter named request</li>
     * </ul>
     *
     * <p>Example: {@code "#commandCode + ':' + #request.clientRequestId"}
     */
    String keyExpression() default "";

    /**
     * Whether to include request body SHA-256 hash as part of the idempotent key.
     * When true, different request bodies with the same key header will be treated
     * as different requests.
     */
    boolean includeBodyHash() default false;

    /**
     * Error message returned when a duplicate request is detected while
     * the original is still processing.
     */
    String message() default "Duplicate request detected";
}
