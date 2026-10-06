package com.auraboot.framework.plugin.extension.iot;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class SimulationWindowContractTest {
    @Test
    void rejectsMissingReversedEmptyAndUnboundedWindows() {
        Instant start = Instant.EPOCH;
        Instant end = start.plusSeconds(60);
        assertThatThrownBy(() -> new BackgroundRuleSimulator.SimWindow(null, end, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackgroundRuleSimulator.SimWindow(start, null, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackgroundRuleSimulator.SimWindow(start, start, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackgroundRuleSimulator.SimWindow(end, start, 10)).isInstanceOf(IllegalArgumentException.class);
        for (int samples : new int[]{0, -1}) {
            assertThatThrownBy(() -> new BackgroundRuleSimulator.SimWindow(start, end, samples)).isInstanceOf(IllegalArgumentException.class);
        }
        var valid = new BackgroundRuleSimulator.SimWindow(start, end, 1);
        assertThat(valid.from()).isEqualTo(start);
        assertThat(valid.to()).isEqualTo(end);
        assertThat(valid.maxSamples()).isEqualTo(1);
    }

    @Test
    void simulationOutcomeCarriesMatchedFrameAndProvenance() {
        var fire = new BackgroundRuleSimulator.WouldFire("device", "rule", "high", Instant.EPOCH, Map.of("temperature", 42));
        var result = new BackgroundRuleSimulator.SimResult("rule", "SQL", 10, List.of(fire), "EMQX archived replay");
        assertThat(result.ruleCode()).isEqualTo("rule");
        assertThat(result.kind()).isEqualTo("SQL");
        assertThat(result.samplesChecked()).isEqualTo(10);
        assertThat(result.wouldFire()).containsExactly(fire);
        assertThat(result.note()).isEqualTo("EMQX archived replay");
        assertThat(fire.deviceCode()).isEqualTo("device");
        assertThat(fire.ruleCode()).isEqualTo("rule");
        assertThat(fire.severity()).isEqualTo("high");
        assertThat(fire.at()).isEqualTo(Instant.EPOCH);
        assertThat(fire.payload()).containsEntry("temperature", 42);
    }
}
