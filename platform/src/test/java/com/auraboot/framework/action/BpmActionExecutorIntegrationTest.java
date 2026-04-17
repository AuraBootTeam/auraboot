package com.auraboot.framework.action;

import com.auraboot.framework.action.executor.BpmActionExecutor;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.TestBpmFixture;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.service.param.query.ProcessInstanceQueryParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BpmActionExecutor (real SmartEngine path)")
class BpmActionExecutorIntegrationTest extends BaseIntegrationTest {

    @Autowired private BpmActionExecutor executor;
    @Autowired private TestBpmFixture fixture;
    @Autowired private SmartEngine smartEngine;

    @Test
    @DisplayName("executionMode=bpm starts process via real SmartEngine and returns instance id")
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

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) executor.execute(actionDef, record);

        String instanceId = (String) result.get("processInstanceId");
        assertThat(instanceId).isNotBlank();

        // Verify the instance is observable via real SmartEngine
        ProcessInstanceQueryParam param = new ProcessInstanceQueryParam();
        param.setTenantId(MetaContext.getCurrentTenantIdAsString());
        param.setBizUniqueId("rec-001");
        List<ProcessInstance> live = smartEngine.getProcessQueryService().findList(param);
        assertThat(live).isNotEmpty();
        assertThat(live.get(0).getInstanceId()).isEqualTo(instanceId);
    }

    @Test
    @DisplayName("rejects duplicate businessKey (running instance check via SmartEngine)")
    void rejectsDuplicateBusinessKey() {
        fixture.deployProcess("executor-dedup");
        Map<String, Object> actionDef = Map.of(
                "code", "submit",
                "executionMode", "bpm",
                "bpm", Map.of("processKey", "executor-dedup", "businessKeyField", "id"));
        Map<String, Object> record = Map.of("id", "rec-dup-1");

        executor.execute(actionDef, record);
        assertThatThrownBy(() -> executor.execute(actionDef, record))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already");
    }

    @Test
    @DisplayName("supports() returns true for executionMode=bpm")
    void supportsDetectsBpmMode() {
        assertThat(executor.supports("bpm")).isTrue();
        assertThat(executor.supports("BPM")).isTrue();
        assertThat(executor.supports("command")).isFalse();
    }

    @Test
    @DisplayName("rejects bracket-style JSONPath (no silent fallback)")
    void rejectsComplexJsonPath() {
        fixture.deployProcess("executor-jsonpath");
        Map<String, Object> actionDef = Map.of(
                "executionMode", "bpm",
                "bpm", Map.of(
                        "processKey", "executor-jsonpath",
                        "businessKeyField", "id",
                        "variables", Map.of("first", "$.items[0]")));
        Map<String, Object> record = Map.of("id", "rec-jp-1", "items", List.of("a", "b"));

        assertThatThrownBy(() -> executor.execute(actionDef, record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bracket syntax");
    }

    @Test
    @DisplayName("rejects blank businessKey value")
    void rejectsBlankBusinessKey() {
        fixture.deployProcess("executor-blank-key");
        Map<String, Object> actionDef = Map.of(
                "executionMode", "bpm",
                "bpm", Map.of("processKey", "executor-blank-key", "businessKeyField", "id"));
        Map<String, Object> record = Map.of("id", "   ");

        assertThatThrownBy(() -> executor.execute(actionDef, record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("missing action.bpm rejected")
    void missingBpmConfigRejected() {
        Map<String, Object> actionDef = Map.of("executionMode", "bpm");
        Map<String, Object> record = Map.of("id", "rec-x");
        assertThatThrownBy(() -> executor.execute(actionDef, record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action.bpm");
    }
}
