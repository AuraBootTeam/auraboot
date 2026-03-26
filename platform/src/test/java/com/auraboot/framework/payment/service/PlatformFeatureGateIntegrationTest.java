package com.auraboot.framework.payment.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for PlatformFeatureGate.
 * Payment is disabled by default in test config — all feature checks should return true.
 */
class PlatformFeatureGateIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PlatformFeatureGate featureGate;

    @Test
    void shouldReturnTrueForAllFeatures_whenPaymentDisabled() {
        Long tenantId = getTestTenant().getId();

        assertThat(featureGate.canCustomizeBranding(tenantId)).isTrue();
        assertThat(featureGate.canUseMultiTenancy(tenantId)).isTrue();
        assertThat(featureGate.canUseSso(tenantId)).isTrue();
        assertThat(featureGate.canUseAuditLog(tenantId)).isTrue();
        assertThat(featureGate.canUseAdvancedRbac(tenantId)).isTrue();
    }

    @Test
    void shouldReturnTrueForPlatformActive_whenPaymentDisabled() {
        Long tenantId = getTestTenant().getId();

        assertThat(featureGate.isPlatformActive(tenantId)).isTrue();
    }

    @Test
    void shouldCheckFeatureViaEntitlement_whenDisabled() {
        Long tenantId = getTestTenant().getId();

        assertThat(featureGate.hasFeature(tenantId, "any.feature.key")).isTrue();
    }
}
