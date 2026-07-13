package com.auraboot.framework.application.security;

/**
 * Declares which paths a scoped JWT may reach.
 *
 * <p>An ordinary platform token has no {@code scope} claim and gets the whole authenticated
 * surface. A <em>scoped</em> token belongs to a subject that is not a platform user at all —
 * an anonymous visitor on an embedded widget, for instance — so it must be confined to a
 * narrow set of endpoints. Publish one of these as a bean per scope; {@link ScopeRestrictionFilter}
 * enforces them.
 *
 * <p>Fail-closed by construction: a token whose scope has no matching policy is rejected, so a
 * new scope cannot reach the API until someone has written down where it is allowed to go.
 */
public interface TokenScopePolicy {

    /** The value of the token's {@code scope} claim this policy governs, e.g. {@code visitor}. */
    String scope();

    /**
     * Ant-style path patterns this scope may reach, e.g. {@code /api/public/cs/**}. Anything else
     * is a 401. Keep these as tight as the feature allows — this is the whole isolation boundary.
     */
    String[] allowedPathPatterns();
}
