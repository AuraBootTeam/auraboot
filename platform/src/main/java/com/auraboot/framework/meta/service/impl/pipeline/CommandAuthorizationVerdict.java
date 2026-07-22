package com.auraboot.framework.meta.service.impl.pipeline;

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
 * @see com.auraboot.framework.meta.service.impl.pipeline.phases.CommandAuthorizationPhase
 */
public record CommandAuthorizationVerdict(Outcome outcome, String permissionCode, String reason) {

    public enum Outcome {
        /** A declared permission was checked and the caller holds it. */
        AUTHORIZED,
        /** No authorization decision was made — see {@link #reason()}. */
        NOT_APPLICABLE
    }

    /** The command declares no permissions, so {@code CommandAuthorizationPhase} had nothing to check. */
    public static final String REASON_NO_DECLARED_PERMISSIONS = "no_declared_permissions";

    /** No user in context (system/scheduled invocation); the phase cannot evaluate a subject. */
    public static final String REASON_NO_USER_CONTEXT = "no_user_context";

    public static CommandAuthorizationVerdict authorized(String permissionCode) {
        return new CommandAuthorizationVerdict(Outcome.AUTHORIZED, permissionCode, null);
    }

    public static CommandAuthorizationVerdict notApplicable(String reason) {
        return new CommandAuthorizationVerdict(Outcome.NOT_APPLICABLE, null, reason);
    }

    public boolean isAuthorized() {
        return outcome == Outcome.AUTHORIZED;
    }
}
