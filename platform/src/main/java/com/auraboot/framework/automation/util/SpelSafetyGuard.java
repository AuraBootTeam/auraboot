package com.auraboot.framework.automation.util;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * Shared SpEL expression safety guard for automation execution paths.
 *
 * <p>Combined with {@code SimpleEvaluationContext.forReadOnlyDataBinding()}, this provides
 * defense-in-depth against ReDoS and expression abuse. It does NOT guarantee
 * absolute security on its own; the read-only evaluation context is the primary
 * containment boundary.
 *
 * <p>Both {@code ControlNodeExecutor} and {@code AutomationTriggerServiceImpl} delegate here
 * to avoid pattern drift between the two evaluation call sites.
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
public final class SpelSafetyGuard {

    /** Max allowed SpEL expression length to prevent ReDoS and expression abuse. */
    public static final int MAX_EXPRESSION_LENGTH = 500;

    /**
     * Reject SpEL expressions that contain type references, method calls,
     * or other constructs that could enable code execution.
     * Combined with SimpleEvaluationContext.forReadOnlyDataBinding() for defense-in-depth.
     */
    static final Pattern DANGEROUS_SPEL_PATTERN = Pattern.compile(
            "(?i)(T\\s*\\(|new\\s+|getClass|forName|invoke|exec|Runtime|Process|System|Thread|Class\\." +
            "|#root|#this|\\bvalueOf\\b|java\\.|javax\\.|org\\.springframework)"
    );

    private SpelSafetyGuard() {
        // utility class
    }

    /**
     * Returns {@code true} if the expression passes all safety checks:
     * non-null, within the length limit, and contains no dangerous patterns.
     *
     * @param expression the SpEL expression to check (may be null)
     * @return {@code true} if safe, {@code false} if the expression should be rejected
     */
    public static boolean isSafe(String expression) {
        if (expression == null) {
            return true; // null is treated as "no expression" by callers
        }
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            log.error("Rejected SpEL expression exceeding max length {}: length={}",
                    MAX_EXPRESSION_LENGTH, expression.length());
            return false;
        }
        if (DANGEROUS_SPEL_PATTERN.matcher(expression).find()) {
            log.error("Rejected dangerous SpEL expression: '{}'", expression);
            return false;
        }
        return true;
    }

    /**
     * Throws {@link IllegalArgumentException} if the expression fails safety checks.
     * Prefer {@link #isSafe(String)} at call sites that handle the rejection themselves.
     *
     * @param expression the SpEL expression to check
     * @throws IllegalArgumentException if the expression is unsafe
     */
    public static void requireSafe(String expression) {
        if (!isSafe(expression)) {
            throw new IllegalArgumentException("Unsafe SpEL expression rejected: " + expression);
        }
    }
}
