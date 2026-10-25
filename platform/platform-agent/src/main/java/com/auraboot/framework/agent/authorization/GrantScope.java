package com.auraboot.framework.agent.authorization;

/**
 * Scope-bound effect grant produced by {@code authorizePlan}. The runtime
 * {@code authorizeIncremental} check matches a {@link RuntimeAuthorizationService.ToolCallIntent}
 * against grants in this shape; matching rules in
 * enterprise/docs/agent/contracts/runtime-authorization.md.
 *
 * <p>Pattern fields ({@code toolRefPattern}, {@code skillCodePattern},
 * {@code argHashConstraint}) accept null = "any".
 */
public record GrantScope(
        EffectClass effect,
        String toolRefPattern,
        String skillCodePattern,
        BlastRadius maxBlastRadius,
        String argHashConstraint,
        EffectLifetime lifetime,
        String policyId,
        int policyVersion
) {

    /** Returns true when {@code intent} satisfies all scope constraints. */
    public boolean matches(RuntimeAuthorizationService.ToolCallIntent intent) {
        if (effect == null || !intent.requiredEffects().contains(effect)) {
            return false;
        }
        if (toolRefPattern != null && !globMatch(toolRefPattern, intent.toolRef())) {
            return false;
        }
        if (skillCodePattern != null && !globMatch(skillCodePattern, intent.skillCode())) {
            return false;
        }
        if (maxBlastRadius != null && intent.blastRadius() != null
                && intent.blastRadius().ordinal() > maxBlastRadius.ordinal()) {
            return false;
        }
        if (argHashConstraint != null
                && (intent.argHash() == null || !intent.argHash().startsWith(argHashConstraint))) {
            return false;
        }
        return true;
    }

    private static boolean globMatch(String pattern, String value) {
        if (value == null) return false;
        if (!pattern.contains("*")) return pattern.equals(value);
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return value.matches(regex);
    }
}
