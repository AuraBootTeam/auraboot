package com.auraboot.framework.meta.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.notification.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DeviceRepairHandler.
 */
@ExtendWith(MockitoExtension.class)
class DeviceRepairHandlerTest {

    @Mock
    private DynamicDataService dynamicDataService;

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private DeviceRepairHandler handler;

    @BeforeEach
    void setUp() {
        // Ensure MetaContext is clean before each test (prevents cross-test contamination
        // from integration tests that set MetaContext on the same thread)
        MetaContext.clear();
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    // =========================================================
    // getHandlerName
    // =========================================================

    @Test
    void getHandlerName_returnsExpectedName() {
        assertThat(handler.getHandlerName()).isEqualTo("deviceRepairHandler");
    }

    // =========================================================
    // shouldCreateWorkOrder — ruleConfig parsing
    // =========================================================

    @Test
    void execute_nullRuleConfig_noWorkOrder() {
        when(metaModelService.isModelExists("device_repair")).thenReturn(false);

        CommandHandlerContext ctx = buildContext(null, "dev-001", null);
        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("handlerExecuted")).isEqualTo(true);
        assertThat(result).doesNotContainKey("workOrderId");
        verify(metaModelService, never()).isModelExists("work_order");
    }

    @Test
    void execute_emptyRuleConfig_noWorkOrder() {
        when(metaModelService.isModelExists("device_repair")).thenReturn(false);

        CommandHandlerContext ctx = buildContext("", "dev-001", null);
        Map<String, Object> result = handler.execute(ctx);

        assertThat(result).doesNotContainKey("workOrderId");
    }

    @Test
    void execute_ruleConfigWithCreateWorkOrderTrue_createsWorkOrder() {
        when(metaModelService.isModelExists("device_repair")).thenReturn(false);
        when(metaModelService.isModelExists("work_order")).thenReturn(false);

        CommandHandlerContext ctx = buildContext("{\"createWorkOrder\":true}", "dev-002", null);
        Map<String, Object> result = handler.execute(ctx);

        assertThat(result).containsKey("workOrderId");
        assertThat(result.get("workOrderStatus")).isEqualTo("created");
    }

    @Test
    void execute_ruleConfigWithCreateWorkOrderFalse_noWorkOrder() {
        when(metaModelService.isModelExists("device_repair")).thenReturn(false);

        CommandHandlerContext ctx = buildContext("{\"createWorkOrder\":false}", "dev-003", null);
        Map<String, Object> result = handler.execute(ctx);

        assertThat(result).doesNotContainKey("workOrderId");
    }

    // =========================================================
    // createRepairRecord — graceful degradation
    // =========================================================

    @Test
    void execute_repairModelNotExist_returnsIdWithoutPersisting() {
        when(metaModelService.isModelExists("device_repair")).thenReturn(false);

        CommandHandlerContext ctx = buildContext(null, "dev-004", null);
        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("repairId")).asString().startsWith("rep_");
        verify(dynamicDataService, never()).create(eq("device_repair"), any());
    }

    @Test
    void execute_repairModelExists_persistsRecord() {
        when(metaModelService.isModelExists("device_repair")).thenReturn(true);

        CommandHandlerContext ctx = buildContext(null, "dev-005", "broken screen");
        handler.execute(ctx);

        verify(dynamicDataService).create(eq("device_repair"), argThat(data ->
                data.containsKey("repair_id") &&
                "dev-005".equals(data.get("device_id")) &&
                "broken screen".equals(data.get("repair_note")) &&
                "initiated".equals(data.get("status"))
        ));
    }

    // =========================================================
    // createWorkOrder — graceful degradation
    // =========================================================

    @Test
    void execute_workOrderModelNotExist_returnsIdWithoutPersisting() {
        when(metaModelService.isModelExists("device_repair")).thenReturn(false);
        when(metaModelService.isModelExists("work_order")).thenReturn(false);

        CommandHandlerContext ctx = buildContext("{\"createWorkOrder\":true}", "dev-006", null);
        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("workOrderId")).asString().startsWith("wo_");
        verify(dynamicDataService, never()).create(eq("work_order"), any());
    }

    @Test
    void execute_workOrderModelExists_persistsWorkOrder() {
        when(metaModelService.isModelExists("device_repair")).thenReturn(false);
        when(metaModelService.isModelExists("work_order")).thenReturn(true);

        CommandHandlerContext ctx = buildContext("{\"createWorkOrder\":true}", "dev-007", "overheating");
        handler.execute(ctx);

        verify(dynamicDataService).create(eq("work_order"), argThat(data ->
                data.containsKey("work_order_id") &&
                "dev-007".equals(data.get("device_id")) &&
                "overheating".equals(data.get("description")) &&
                "created".equals(data.get("status")) &&
                "device_repair".equals(data.get("type"))
        ));
    }

    // =========================================================
    // notifyTechnicians — skips when no userId in MetaContext
    // =========================================================

    @Test
    void execute_noCurrentUserId_skipsNotification() {
        // MetaContext.getCurrentUserId() returns null when no context is set
        when(metaModelService.isModelExists("device_repair")).thenReturn(false);

        CommandHandlerContext ctx = buildContext(null, "dev-008", null);
        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("notificationSent")).isEqualTo(true);
        // notificationService must NOT be called when userId is null
        verify(notificationService, never()).sendInApp(anyLong(), any(), any(), any(), any(), any());
    }

    // =========================================================
    // execute() — result structure
    // =========================================================

    @Test
    void execute_returnsRequiredResultFields() {
        when(metaModelService.isModelExists("device_repair")).thenReturn(false);

        CommandHandlerContext ctx = buildContext(null, "dev-009", "battery failure");
        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("repairId")).asString().startsWith("rep_");
        assertThat(result.get("repairStatus")).isEqualTo("initiated");
        assertThat(result).containsKey("repairStartTime");
        assertThat(result.get("handlerExecuted")).isEqualTo(true);
        assertThat(result.get("notificationSent")).isEqualTo(true);
    }

    @Test
    void execute_payloadRepairNote_passedToRepairRecord() {
        when(metaModelService.isModelExists("device_repair")).thenReturn(true);

        CommandHandlerContext ctx = buildContext(null, "dev-010", "cracked display");
        handler.execute(ctx);

        verify(dynamicDataService).create(eq("device_repair"), argThat(data ->
                "cracked display".equals(data.get("repair_note"))
        ));
    }

    // =========================================================
    // Helper
    // =========================================================

    private CommandHandlerContext buildContext(String ruleConfig, String deviceId, String repairNote) {
        Map<String, Object> payload = repairNote != null ? Map.of("repairNote", repairNote) : null;
        return CommandHandlerContext.builder()
                .commandCode("cmd_repair")
                .modelCode("device")
                .targetRecordId(deviceId)
                .payload(payload)
                .ruleConfig(ruleConfig)
                .userId(1L)
                .tenantId(1L)
                .build();
    }
}
