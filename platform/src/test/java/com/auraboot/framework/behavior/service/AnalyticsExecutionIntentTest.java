package com.auraboot.framework.behavior.service;

import com.auraboot.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AnalyticsExecutionIntentTest {
    @Test
    void preservesOnlyTheExplicitTaskGoal() {
        var input = Map.of("type", "agent_task", "goal", "Review the selected orders");
        assertThat(AnalyticsExecutionIntent.parse(input).toMap()).isEqualTo(input);
    }
    @Test
    void rejectsUnboundedOrAuthorityBearingIntents() {
        for (Object input : List.of(
                Map.of("type", "command", "goal", "Review"),
                Map.of("type", "agent_task", "goal", " "),
                Map.of("type", "agent_task", "goal", "x".repeat(4001)),
                Map.of("type", "agent_task", "goal", "Review", "runPid", "forged"),
                Map.of("type", "agent_task", "goal", "Review", "permissions", "admin"),
                Map.of("type", "agent_task"), "Review")) {
            assertThatThrownBy(() -> AnalyticsExecutionIntent.parse(input))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> AnalyticsExecutionIntent.parse(null)).isInstanceOf(BusinessException.class);
    }
}
