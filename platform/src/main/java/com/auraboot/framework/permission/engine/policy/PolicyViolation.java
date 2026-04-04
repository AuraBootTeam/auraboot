package com.auraboot.framework.permission.engine.policy;

/**
 * Represents a single policy rule violation.
 *
 * @param ruleKey the policy rule key that was violated (e.g. "maxApprovalAmount")
 * @param message human-readable description of the violation
 */
public record PolicyViolation(String ruleKey, String message) {
}
