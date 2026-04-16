package com.auraboot.framework.action;

import com.auraboot.framework.action.executor.BpmActionExecutor;
import com.auraboot.framework.bpm.TestBpmFixture;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BpmActionExecutor")
class BpmActionExecutorIntegrationTest extends BaseIntegrationTest {

    @Autowired private BpmActionExecutor executor;
    @Autowired private TestBpmFixture fixture;

    @Test
    @DisplayName("executes action with executionMode=bpm and starts process")
    void executionModeBpmStartsProcess() {
        fixture.deployProcess("executor-demo");
        Map<String, Object> actionDef = Map.of(
                "code", "submit_demo",
                "executionMode", "bpm",
                "bpm", Map.of(
                        "processKey", "executor-demo",
                        "businessKeyField", "id",
                        "variables", Map.of("amount", "$.amount")));
        Map<String, Object> record = Map.of("id", "rec-001", "amount", 100);

        Object result = executor.execute(actionDef, record);

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) result;
        assertThat(r).containsKey("processInstanceId");
        assertThat(r.get("processInstanceId")).isInstanceOf(String.class);
        assertThat((String) r.get("processInstanceId")).isNotBlank();
    }

    @Test
    @DisplayName("rejects duplicate businessKey")
    void rejectsDuplicateBusinessKey() {
        fixture.deployProcess("executor-dedup");
        Map<String, Object> actionDef = Map.of(
                "code", "submit",
                "executionMode", "bpm",
                "bpm", Map.of("processKey", "executor-dedup", "businessKeyField", "id"));
        Map<String, Object> record = Map.of("id", "rec-dup-1");

        executor.execute(actionDef, record);
        assertThatThrownBy(() -> executor.execute(actionDef, record))
                .hasMessageContaining("already");
    }

    @Test
    @DisplayName("supports() returns true for executionMode=bpm")
    void supportsDetectsBpmMode() {
        assertThat(executor.supports("bpm")).isTrue();
        assertThat(executor.supports("command")).isFalse();
    }
}
