package com.auraboot.framework.plugin.extension.iot;

import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor.RuleKind;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor.RuleScope;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor.RuleView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@link BackgroundRuleAccessor} using an in-memory fake.
 */
class BackgroundRuleAccessorContractTest {

    private InMemoryRuleAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = new InMemoryRuleAccessor();
        accessor.put(new RuleView("rule-dev-1", RuleScope.DEVICE, "sensor-A",
                RuleKind.SQL, "temperature > 90", "{\"alarm\":true}",
                "CRITICAL", 60, true, 100L));
        accessor.put(new RuleView("rule-dev-2", RuleScope.DEVICE, "sensor-A",
                RuleKind.SQL, "temperature < 0", "{\"alarm\":true}",
                "MINOR", 0, false, 100L));
        accessor.put(new RuleView("rule-prod-1", RuleScope.PRODUCT, "temp-product",
                RuleKind.CHAIN, "chain-pid-001", "{}",
                "MAJOR", 30, true, 100L));
        accessor.put(new RuleView("rule-tenant-1", RuleScope.TENANT, null,
                RuleKind.SMART_ENGINE, "escalation-v1", "{}",
                "WARNING", 0, true, 100L));
        // Cross-tenant rule with same code prefix to verify isolation.
        accessor.put(new RuleView("rule-dev-1", RuleScope.DEVICE, "sensor-A",
                RuleKind.SQL, "humidity > 80", "{}",
                "MAJOR", 0, true, 200L));
    }

    @Test
    void findActiveByScope_returnsOnlyEnabledTenantScopedRules() {
        List<RuleView> result = accessor.findActiveByScope(100L, RuleScope.DEVICE, "sensor-A");

        // rule-dev-2 is disabled — must be excluded.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).code()).isEqualTo("rule-dev-1");
        assertThat(result.get(0).severity()).isEqualTo("CRITICAL");
        assertThat(result.get(0).enabled()).isTrue();
    }

    @Test
    void findActiveByScope_productScope() {
        List<RuleView> result = accessor.findActiveByScope(100L, RuleScope.PRODUCT, "temp-product");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).kind()).isEqualTo(RuleKind.CHAIN);
    }

    @Test
    void findActiveByScope_tenantScopeIgnoresKey() {
        List<RuleView> a = accessor.findActiveByScope(100L, RuleScope.TENANT, null);
        List<RuleView> b = accessor.findActiveByScope(100L, RuleScope.TENANT, "anything");

        assertThat(a).hasSize(1);
        assertThat(b).hasSize(1);
        assertThat(a.get(0).code()).isEqualTo(b.get(0).code());
    }

    @Test
    void findActiveByScope_isolatesAcrossTenants() {
        RuleView t100 = accessor.findActiveByScope(100L, RuleScope.DEVICE, "sensor-A").get(0);
        RuleView t200 = accessor.findActiveByScope(200L, RuleScope.DEVICE, "sensor-A").get(0);

        assertThat(t100.expression()).isEqualTo("temperature > 90");
        assertThat(t200.expression()).isEqualTo("humidity > 80");
    }

    @Test
    void findActiveByScope_noMatchReturnsEmptyList() {
        assertThat(accessor.findActiveByScope(999L, RuleScope.DEVICE, "sensor-A"))
                .isNotNull()
                .isEmpty();
    }

    @Test
    void findByCode_returnsDisabledRuleTooButFlagged() {
        Optional<RuleView> result = accessor.findByCode(100L, "rule-dev-2");

        assertThat(result).isPresent();
        assertThat(result.get().enabled()).isFalse();
    }

    @Test
    void findByCode_unknownReturnsEmpty() {
        assertThat(accessor.findByCode(100L, "nope")).isEmpty();
    }

    /** In-memory implementation used to assert the contract shape. */
    static final class InMemoryRuleAccessor implements BackgroundRuleAccessor {
        private final List<RuleView> rules = new ArrayList<>();

        void put(RuleView rule) {
            rules.add(rule);
        }

        @Override
        public List<RuleView> findActiveByScope(long tenantId, RuleScope scope, String scopeKey) {
            return rules.stream()
                    .filter(r -> r.tenantId() == tenantId)
                    .filter(RuleView::enabled)
                    .filter(r -> r.scope() == scope)
                    .filter(r -> scope == RuleScope.TENANT
                            || (r.scopeKey() != null && r.scopeKey().equals(scopeKey)))
                    .toList();
        }

        @Override
        public Optional<RuleView> findByCode(long tenantId, String ruleCode) {
            return rules.stream()
                    .filter(r -> r.tenantId() == tenantId && r.code().equals(ruleCode))
                    .findFirst();
        }
    }
}
