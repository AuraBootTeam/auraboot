package com.auraboot.framework.meta.service.impl.pipeline;

import java.util.List;

/**
 * What the command boundary actually decided about the caller.
 *
 * <p>Until this existed, the boundary expressed itself by <em>not throwing</em>, which conflates two
 * very different states: "I checked a declared permission and the caller has it" and "there was
 * nothing declared, so I checked nothing". Both reached the handler as a plain {@code return}.</p>
 *
 * <p>That distinction is load-bearing. Downstream data access may only inherit the boundary's
 * authority when the boundary actually exercised it — a command that declares no permissions has
 * granted nothing, and treating its silence as approval would turn it into a tenant-wide write
 * oracle. Recording the verdict is the prerequisite for that inheritance; it does not itself grant
 * anything.</p>
 *
 * <p>A denial used to be expressed only as a thrown {@code FORBIDDEN} — a decision that left no
 * trace on the context. {@link Outcome#DENIED} makes refusal a first-class recorded outcome
 * alongside grant and abstention: the boundary still throws, but the verdict now says <em>why</em>
 * (which of {@link #requiredPermissions()} the caller lacked), which is what an audit trail or a
 * future decision plan needs to be explainable rather than silent. This is the algebraically
 * complete {@code Authorized | Denied | NotApplicable} shape the authorization plan builds on;
 * recording DENIED changes nothing about enforcement.</p>
 *
 * @see com.auraboot.framework.meta.service.impl.pipeline.phases.CommandAuthorizationPhase
 */
public record CommandAuthorizationVerdict(
        Outcome outcome, String permissionCode, String reason, List<String> requiredPermissions) {

    public enum Outcome {
        /** A declared permission was checked and the caller holds it. */
        AUTHORIZED,
        /** A declared permission was checked and the caller holds none of it — see {@link #requiredPermissions()}. */
        DENIED,
        /** No authorization decision was made — see {@link #reason()}. */
        NOT_APPLICABLE
    }

    /** The command declares no permissions, so {@code CommandAuthorizationPhase} had nothing to check. */
    public static final String REASON_NO_DECLARED_PERMISSIONS = "no_declared_permissions";

    /** No user in context (system/scheduled invocation); the phase cannot evaluate a subject. */
    public static final String REASON_NO_USER_CONTEXT = "no_user_context";

    /** A declared permission was checked and the caller holds none of {@link #requiredPermissions()}. */
    public static final String REASON_PERMISSION_DENIED = "permission_denied";

    public static CommandAuthorizationVerdict authorized(String permissionCode) {
        return new CommandAuthorizationVerdict(Outcome.AUTHORIZED, permissionCode, null, List.of());
    }

    public static CommandAuthorizationVerdict notApplicable(String reason) {
        return new CommandAuthorizationVerdict(Outcome.NOT_APPLICABLE, null, reason, List.of());
    }

    /**
     * The caller was checked against declared permissions and holds none of them. Carries the set it
     * failed so the refusal is explainable; the phase still throws {@code FORBIDDEN} after recording it.
     */
    public static CommandAuthorizationVerdict denied(List<String> requiredPermissions) {
        return new CommandAuthorizationVerdict(
                Outcome.DENIED, null, REASON_PERMISSION_DENIED, List.copyOf(requiredPermissions));
    }

    public boolean isAuthorized() {
        return outcome == Outcome.AUTHORIZED;
    }

    public boolean isDenied() {
        return outcome == Outcome.DENIED;
    }
}
