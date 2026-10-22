package com.auraboot.framework.agent.runtime.policy;

import com.auraboot.framework.aurabot.skill.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RiskScale")
class RiskScaleTest {

    @Test
    @DisplayName("normalizes canonical, legacy and AuraBot aliases onto one versioned scale")
    void normalizesAllSupportedAliases() {
        assertThat(RiskScale.VERSION).isEqualTo("risk-scale/v1");
        assertThat(RiskScale.parse("L0")).isEqualTo(RiskScale.L0);
        assertThat(RiskScale.parse("r2")).isEqualTo(RiskScale.L2);
        assertThat(RiskScale.parse("medium")).isEqualTo(RiskScale.L2);
        assertThat(RiskScale.parse("HIGH")).isEqualTo(RiskScale.L3);
        assertThat(RiskScale.parse("critical")).isEqualTo(RiskScale.L4);
    }

    @Test
    @DisplayName("drives confirmation and human approval boundaries")
    void drivesApprovalBoundaries() {
        assertThat(RiskScale.L1.requiresConfirmation()).isFalse();
        assertThat(RiskScale.L2.requiresConfirmation()).isTrue();
        assertThat(RiskScale.L2.requiresHumanApproval()).isFalse();
        assertThat(RiskScale.L3.requiresHumanApproval()).isTrue();
        assertThat(RiskScale.L4.requiresHumanApproval()).isTrue();
    }

    @Test
    @DisplayName("legacy AuraBot persistence decodes to the same canonical levels")
    void auraBotCompatibilityUsesCanonicalScale() {
        assertThat(RiskLevel.fromCode("low").canonicalCode()).isEqualTo("L0");
        assertThat(RiskLevel.fromCode("L2")).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.fromCode("r3")).isEqualTo(RiskLevel.HIGH);
        assertThat(RiskLevel.CRITICAL.atLeast(RiskLevel.HIGH)).isTrue();
    }
}
