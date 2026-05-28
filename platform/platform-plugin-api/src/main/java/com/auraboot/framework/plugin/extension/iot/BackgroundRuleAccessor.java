package com.auraboot.framework.plugin.extension.iot;

import java.util.List;
import java.util.Optional;

/**
 * Rule-discovery bridge for plugin background components — the IoT rule
 * engine worker and EMQX hook consumer both need to enumerate the rules
 * that apply to an incoming telemetry payload by scope (device / product /
 * tenant) without coupling to the platform-internal rule service.
 *
 * <p>Follows the same null-fallback SPI contract as the other
 * {@code Background*Accessor} interfaces.
 *
 * <p><b>Tenant isolation:</b> both lookup methods take an explicit
 * {@code tenantId}; the rule registry MUST NOT return cross-tenant rows.
 *
 * @since 2.6.0
 */
public interface BackgroundRuleAccessor {

    /**
     * Find all enabled rules at the given scope, ordered by severity
     * descending then code ascending (deterministic for replay).
     *
     * @param tenantId owning tenant id (must be {@code &gt; 0})
     * @param scope    scope kind (must not be null)
     * @param scopeKey scope key:
     *                 <ul>
     *                 <li>{@link RuleScope#DEVICE} &rarr; {@code deviceCode}</li>
     *                 <li>{@link RuleScope#PRODUCT} &rarr; {@code productKey}</li>
     *                 <li>{@link RuleScope#TENANT} &rarr; ignored / may be null (tenant-wide rules)</li>
     *                 </ul>
     * @return non-null list; empty when no enabled rules match
     */
    List<RuleView> findActiveByScope(long tenantId, RuleScope scope, String scopeKey);

    /**
     * Look up a single rule by tenant-unique business code, regardless of
     * enabled state. Callers should check {@link RuleView#enabled()} before
     * evaluating.
     *
     * @param tenantId owning tenant id
     * @param ruleCode tenant-unique rule code (not blank)
     * @return rule snapshot, or empty when not found
     */
    Optional<RuleView> findByCode(long tenantId, String ruleCode);

    /**
     * Scope kind of a rule.
     */
    enum RuleScope {
        /** Rule binds to a single device by {@code deviceCode}. */
        DEVICE,
        /** Rule applies to every device of a product by {@code productKey}. */
        PRODUCT,
        /** Rule applies tenant-wide; {@code scopeKey} is ignored. */
        TENANT
    }

    /**
     * Expression / action kind for the rule body.
     */
    enum RuleKind {
        /** SQL-like predicate evaluated over telemetry payload. */
        SQL,
        /** Rule chain (decision flow) reference. */
        CHAIN,
        /** SmartEngine process reference (e.g. for approval-style escalation). */
        SMART_ENGINE
    }

    /**
     * Immutable rule snapshot.
     *
     * @param code            tenant-unique rule code
     * @param scope           rule scope kind
     * @param scopeKey        device code / product key / null per scope
     * @param kind            expression kind
     * @param expression      kind-specific body (SQL text / chain pid /
     *                        process key); never null but may be empty for
     *                        rules pending authoring
     * @param actions         action JSON (alarm severity, dispatch target,
     *                        bpm process key, etc.); never null but may be
     *                        empty
     * @param severity        national-standard alarm severity (e.g.
     *                        {@code CRITICAL / MAJOR / MINOR / WARNING});
     *                        never null for alarm-emitting rules
     * @param cooldownSeconds suppression window between repeated firings; 0
     *                        means no cooldown
     * @param enabled         whether the rule should be evaluated
     * @param tenantId        owning tenant
     */
    record RuleView(
            String code,
            RuleScope scope,
            String scopeKey,
            RuleKind kind,
            String expression,
            String actions,
            String severity,
            int cooldownSeconds,
            boolean enabled,
            long tenantId) {
    }
}
