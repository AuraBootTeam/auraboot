package com.auraboot.framework.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Operational metrics for the User Soul Profile pipeline (PR-75 + PR-76).
 *
 * <ul>
 *   <li>{@code auraboot_user_soul_profile_derivation_total{tenant, outcome}} —
 *       emitted by {@code UserSoulProfileDeriver}.
 *       {@code outcome} ∈ {drafted, skipped_no_change,
 *       skipped_too_little_signal, skipped_forgotten, failed}.</li>
 *   <li>{@code auraboot_user_soul_profile_activation_total{tenant}} —
 *       emitted by {@code UserSoulProfileActivator} on DRAFT→ACTIVE.</li>
 *   <li>{@code auraboot_user_soul_profile_stale_flagged_total{tenant}} —
 *       emitted by {@code UserSoulProfileStalenessDetector}.</li>
 *   <li>{@code auraboot_user_soul_profile_user_edit_total{tenant, action}} —
 *       emitted by {@code UserSoulProfileEditor}.
 *       {@code action} ∈ {pin, hide, edit, reset, hide_profile, forget}.</li>
 * </ul>
 *
 * <p>Cardinality: O(tenant × outcome) / O(tenant × action) — bounded enums,
 * safe for Prometheus.
 */
@Component
@RequiredArgsConstructor
public class UserSoulProfileMetrics {

    public static final String DERIVATION_TOTAL = "auraboot_user_soul_profile_derivation_total";
    public static final String ACTIVATION_TOTAL = "auraboot_user_soul_profile_activation_total";
    public static final String STALE_FLAGGED_TOTAL = "auraboot_user_soul_profile_stale_flagged_total";
    public static final String USER_EDIT_TOTAL = "auraboot_user_soul_profile_user_edit_total";
    public static final String MANUAL_DERIVE_TOTAL = "auraboot_user_soul_profile_manual_derive_total";

    public static final String OUTCOME_DRAFTED = "drafted";
    public static final String OUTCOME_SKIPPED_NO_CHANGE = "skipped_no_change";
    public static final String OUTCOME_SKIPPED_TOO_LITTLE_SIGNAL = "skipped_too_little_signal";
    public static final String OUTCOME_SKIPPED_FORGOTTEN = "skipped_forgotten";
    public static final String OUTCOME_FAILED = "failed";

    public static final String EDIT_PIN = "pin";
    public static final String EDIT_HIDE = "hide";
    public static final String EDIT_EDIT = "edit";
    public static final String EDIT_RESET = "reset";
    public static final String EDIT_HIDE_PROFILE = "hide_profile";
    public static final String EDIT_FORGET = "forget";

    public static final String MANUAL_OUTCOME_TRIGGERED = "triggered";
    public static final String MANUAL_OUTCOME_RATE_LIMITED = "rate_limited";

    private final MeterRegistry registry;

    public void recordDerivation(Long tenantId, String outcome) {
        Counter.builder(DERIVATION_TOTAL)
                .description("User Soul Profile derivation outcomes per tenant")
                .tag("tenant", tenantLabel(tenantId))
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment();
    }

    public void recordActivation(Long tenantId) {
        Counter.builder(ACTIVATION_TOTAL)
                .description("User Soul Profile DRAFT→ACTIVE activations per tenant")
                .tag("tenant", tenantLabel(tenantId))
                .register(registry)
                .increment();
    }

    public void recordStaleFlagged(Long tenantId) {
        Counter.builder(STALE_FLAGGED_TOTAL)
                .description("User Soul Profile stale-flag events per tenant")
                .tag("tenant", tenantLabel(tenantId))
                .register(registry)
                .increment();
    }

    public void recordUserEdit(Long tenantId, String action) {
        Counter.builder(USER_EDIT_TOTAL)
                .description("User Soul Profile user edits per tenant + action")
                .tag("tenant", tenantLabel(tenantId))
                .tag("action", action == null ? "unknown" : action)
                .register(registry)
                .increment();
    }

    public void recordManualDerive(Long tenantId, String outcome) {
        Counter.builder(MANUAL_DERIVE_TOTAL)
                .description("User Soul Profile manual derive-now triggers per tenant + outcome")
                .tag("tenant", tenantLabel(tenantId))
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment();
    }

    private static String tenantLabel(Long tenantId) {
        return tenantId == null ? "unknown" : tenantId.toString();
    }
}
