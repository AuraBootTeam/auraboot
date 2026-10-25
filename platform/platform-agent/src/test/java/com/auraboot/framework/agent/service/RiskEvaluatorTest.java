package com.auraboot.framework.agent.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RiskEvaluatorTest {
    private final RiskEvaluator riskEvaluator = new RiskEvaluator();

    @Test
    void evaluate_deleteIntent_returnsL4WithPropose() {
        assertThat(riskEvaluator.evaluate("delete", 1)).isEqualTo("L4");
        assertThat(riskEvaluator.deriveActionability("delete")).isEqualTo("propose");
    }

    @Test
    void evaluate_queryIntent_returnsL0ReadOnly() {
        assertThat(riskEvaluator.evaluate("query", 1)).isEqualTo("L0");
        assertThat(riskEvaluator.deriveActionability("query")).isEqualTo("read_only");
    }

    @Test
    void evaluate_batchElevation() {
        assertThat(riskEvaluator.evaluate("create", 15)).isEqualTo("L2");
        assertThat(riskEvaluator.evaluate("create", 150)).isEqualTo("L3");
    }

    @Test
    void deriveFromCommandType_mapsCorrectly() {
        assertThat(riskEvaluator.deriveFromCommandType("create")).isEqualTo("L1");
        assertThat(riskEvaluator.deriveFromCommandType("update")).isEqualTo("L1");
        assertThat(riskEvaluator.deriveFromCommandType("state_transition")).isEqualTo("L1");
        assertThat(riskEvaluator.deriveFromCommandType("automate")).isEqualTo("L2");
        assertThat(riskEvaluator.deriveFromCommandType("delete")).isEqualTo("L4");
        assertThat(riskEvaluator.deriveFromCommandType("unknown_type")).isEqualTo("L1");
    }
}
