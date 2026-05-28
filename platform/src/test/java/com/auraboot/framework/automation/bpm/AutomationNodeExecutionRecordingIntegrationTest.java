package com.auraboot.framework.automation.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.dto.AutomationNodeExecutionDTO;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.AutomationLog;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.automation.mapper.AutomationNodeExecutionMapper;
import com.auraboot.framework.automation.service.AutomationExecutionQueryService;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G5 — node-execution recording end-to-end IT.
 *
 * <p>Drives a real SmartEngine run through {@link AutomationProcessRuntime} with a
 * marker action executor, then asserts that
 * {@link com.auraboot.framework.automation.entity.AutomationNodeExecution} rows
 * were persisted with the right tenant_id, automation_log_id, and status —
 * including the failure path where the action throws and the row must end up
 * {@code status='failed'} with the error message preserved.
 *
 * <p>Runs against the real (isolated) PostgreSQL stack, exactly like
 * {@link AutomationProcessRuntimeIntegrationTest}.
 */
@Slf4j
@DisplayName("Automation node-execution recording (G5)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AutomationNodeExecutionRecordingIntegrationTest extends BaseIntegrationTest {

    @TestConfiguration
    static class MarkerConfig {
        @Bean
        ActionExecutor g5MarkerActionExecutor() {
            return new ActionExecutor() {
                @Override
                public boolean supports(String actionType) {
                    return "g5_marker".equals(actionType) || "g5_failure".equals(actionType);
                }

                @Override
                public Object execute(AutomationAction action, Map<String, Object> context) {
                    if ("g5_failure".equals(action.getType())) {
                        throw new RuntimeException("g5 synthetic failure");
                    }
                    return Map.of("ok", true);
                }
            };
        }
    }

    @Autowired
    private AutomationProcessRuntime runtime;

    @Autowired
    private AutomationNodeExecutionMapper nodeExecutionMapper;

    @Autowired
    private AutomationExecutionQueryService queryService;

    @Autowired
    private com.auraboot.framework.automation.trigger.AutomationTriggerService triggerService;

    private long syntheticLogId;

    @BeforeEach
    void seedSyntheticLog() {
        // Each test gets a unique synthetic log id; we never actually insert into
        // ab_automation_log here — the FK in the schema is purely informational
        // (no PG REFERENCES). The IT for the executeAutomation path (below) does
        // exercise a real log row via the trigger service.
        syntheticLogId = System.nanoTime();
    }

    private Automation markerAutomation(String actionType) {
        Automation a = new Automation();
        a.setPid("G5IT" + System.currentTimeMillis() + actionType);
        a.setName("G5 marker automation");
        a.setTenantId(MetaContext.getCurrentTenantId());
        a.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "trig", "type", "trigger-record-create",
                                "data", Map.of("label", "On create", "config", Map.of())),
                        Map.of("id", "act1", "type", "action-g5-marker",
                                "data", Map.of("label", "Marker",
                                        "config", Map.of("actionType", actionType)))),
                "edges", List.of(
                        Map.of("id", "e1", "source", "trig", "target", "act1"))));
        a.setEnabled(true);
        return a;
    }

    @Test
    void successfulRun_writesCompletedRowForEachActionNode() {
        Automation automation = markerAutomation("g5_marker");
        runtime.deploy(automation);

        runtime.run(automation, "rec-ok", Map.of("event", "create"), syntheticLogId);

        List<AutomationNodeExecutionDTO> statuses =
                queryService.getNodeStatusesByLogId(syntheticLogId);
        assertThat(statuses)
                .as("a row should be written for each action node entered (1 action -> 1 row)")
                .hasSize(1);
        AutomationNodeExecutionDTO row = statuses.get(0);
        assertThat(row.getNodeId()).isEqualTo("act1");
        assertThat(row.getStatus()).isEqualTo(StatusConstants.COMPLETED);
        assertThat(row.getErrorMessage()).isNull();
        assertThat(row.getStartedAt()).isNotNull();
        assertThat(row.getCompletedAt()).isNotNull();
    }

    @Test
    void failedRun_writesFailedRowAndPreservesErrorMessage() {
        Automation automation = markerAutomation("g5_failure");
        runtime.deploy(automation);

        try {
            runtime.run(automation, "rec-fail", Map.of("event", "create"), syntheticLogId);
        } catch (RuntimeException expected) {
            // Per red line §8 the delegate must propagate the original action exception
            // after stamping the failed row — swallowing would mask the run failure.
        }

        List<AutomationNodeExecutionDTO> statuses =
                queryService.getNodeStatusesByLogId(syntheticLogId);
        assertThat(statuses).hasSize(1);
        AutomationNodeExecutionDTO row = statuses.get(0);
        assertThat(row.getStatus()).isEqualTo(StatusConstants.FAILED);
        assertThat(row.getErrorMessage()).contains("g5 synthetic failure");
        assertThat(row.getCompletedAt())
                .as("failed rows must still be closed with completedAt for run-history UI")
                .isNotNull();
    }

    @Test
    void rowsAreTenantScoped_otherTenantsCannotSee() {
        Automation automation = markerAutomation("g5_marker");
        runtime.deploy(automation);
        runtime.run(automation, "rec-tenant", Map.of("event", "create"), syntheticLogId);

        // Verify the row was actually written to this tenant.
        Long realTenantId = MetaContext.getCurrentTenantId();
        QueryWrapper<com.auraboot.framework.automation.entity.AutomationNodeExecution> q =
                new QueryWrapper<>();
        q.eq("tenant_id", realTenantId).eq("automation_log_id", syntheticLogId);
        assertThat(nodeExecutionMapper.selectList(q)).isNotEmpty();

        // Switch tenant context to an impossible id; query service must return empty.
        MetaContext.clear();
        MetaContext.setContext(-9999L, getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
        try {
            List<AutomationNodeExecutionDTO> seen =
                    queryService.getNodeStatusesByLogId(syntheticLogId);
            assertThat(seen)
                    .as("rows must be tenant-scoped: cross-tenant access returns empty")
                    .isEmpty();
        } finally {
            applyTestMetaContext();
        }
    }

    @Test
    void executeAutomation_writesNodeRowsLinkedToRealLog() {
        // End-to-end: the trigger service inserts the real ab_automation_log row,
        // threads its id through runtime.run(), and the delegate writes node rows
        // linked to it. This is what production traffic hits.
        Automation automation = markerAutomation("g5_marker");
        automation.setTriggerType("manual");
        runtime.deploy(automation);

        AutomationLog logEntry = triggerService.executeAutomation(
                automation, "rec-real", Map.of("event", "create"));

        assertThat(logEntry.getId())
                .as("trigger service must persist a real log row id")
                .isNotNull();

        List<AutomationNodeExecutionDTO> statuses =
                queryService.getNodeStatusesByLogId(logEntry.getId());
        assertThat(statuses).hasSize(1);
        assertThat(statuses.get(0).getStatus()).isEqualTo(StatusConstants.COMPLETED);
        assertThat(statuses.get(0).getNodeId()).isEqualTo("act1");
    }
}
