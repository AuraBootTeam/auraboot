package com.auraboot.framework.plugin.extension.iot;

import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor.RuleKind;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor.RuleScope;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor.RuleView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shape-only pin of the {@link BackgroundRuleAccessor} SPI records
 * ({@link RuleView}, {@link RuleScope}, {@link RuleKind}), per
 * testing-layering.md §2.1 (shape self-check exemption).
 *
 * Scope filtering, enabled-only selection and tenant isolation are
 * persistence semantics and are NOT asserted here — the former fake's own
 * stream filters were asserted against themselves. They are owed to
 * real-stack tests against {@code IotRuleAccessorImpl}; tracked in workspace
 * {@code tools/fake-db-registry.tsv}.
 */
class BackgroundRuleAccessorShapeTest {

    @Test
    void ruleScopeAndKindEnumsExposeTheDocumentedValues() {
        assertThat(RuleScope.values()).containsExactly(RuleScope.DEVICE, RuleScope.PRODUCT, RuleScope.TENANT);
        assertThat(RuleKind.values()).contains(RuleKind.SQL, RuleKind.CHAIN, RuleKind.SMART_ENGINE);
    }

    @Test
    void ruleViewCarriesScopeExpressionActionsCooldownAndEnabledFlag() {
        RuleView rule = new RuleView("rule-dev-1", RuleScope.DEVICE, "sensor-A",
                RuleKind.SQL, "temperature > 90", "{\"alarm\":true}",
                "CRITICAL", 60, true, 100L);
        RuleView tenantRule = new RuleView("rule-tenant-1", RuleScope.TENANT, null,
                RuleKind.SMART_ENGINE, "escalation-v1", "{}",
                "WARNING", 0, false, 100L);

        assertThat(rule.code()).isEqualTo("rule-dev-1");
        assertThat(rule.scope()).isEqualTo(RuleScope.DEVICE);
        assertThat(rule.scopeKey()).isEqualTo("sensor-A");
        assertThat(rule.kind()).isEqualTo(RuleKind.SQL);
        assertThat(rule.expression()).isEqualTo("temperature > 90");
        assertThat(rule.actions()).isEqualTo("{\"alarm\":true}");
        assertThat(rule.severity()).isEqualTo("CRITICAL");
        assertThat(rule.cooldownSeconds()).isEqualTo(60);
        assertThat(rule.enabled()).isTrue();
        assertThat(rule.tenantId()).isEqualTo(100L);

        assertThat(tenantRule.scopeKey()).isNull();
        assertThat(tenantRule.enabled()).isFalse();
    }
}
