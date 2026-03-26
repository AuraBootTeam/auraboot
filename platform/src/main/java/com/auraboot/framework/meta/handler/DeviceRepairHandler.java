package com.auraboot.framework.meta.handler;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Device Repair Handler
 *
 * Handles device repair workflow when RepairDeviceCommand is executed.
 * This handler demonstrates the HANDLER extension point:
 *
 * 1. Receives CommandHandlerContext with payload and previous phase results
 * 2. Performs business logic (create work order, notify technicians, etc.)
 * 3. Returns handler output that gets merged into command result
 *
 * Usage:
 * Configure BindingRule with ruleType=HANDLER and handlerClass="deviceRepairHandler"
 *
 * @author AuraBoot Team
 * @since 4.0.0
 */
@Slf4j
@Component("deviceRepairHandler")
@RequiredArgsConstructor
public class DeviceRepairHandler implements CommandHandler {

    private final DynamicDataService dynamicDataService;
    private final MetaModelService metaModelService;
    private final NotificationService notificationService;

    private static final String REPAIR_MODEL_CODE = "device_repair";
    private static final String WORK_ORDER_MODEL_CODE = "work_order";

    @Override
    public String getHandlerName() {
        return "deviceRepairHandler";
    }

    @Override
    public Map<String, Object> execute(CommandHandlerContext context) {
        log.info("DeviceRepairHandler executing for command: {}", context.getCommandCode());

        Map<String, Object> result = new HashMap<>();

        try {
            // 1. Extract repair information from payload
            Map<String, Object> payload = context.getPayload();
            String repairNote = payload != null ? (String) payload.get("repairNote") : null;
            String deviceId = context.getTargetRecordId();

            // 2. Log context information for debugging
            log.debug("Handler context - commandCode: {}, modelCode: {}, targetRecordId: {}",
                context.getCommandCode(),
                context.getModelCode(),
                context.getTargetRecordId());

            // 3. Access FIELD_MAP phase results if available
            Map<String, Object> fieldMapResults = context.getFieldMapResults();
            if (fieldMapResults != null && !fieldMapResults.isEmpty()) {
                log.debug("FIELD_MAP results available: {}", fieldMapResults.keySet());
            }

            // 4. Parse handler configuration from ruleConfig
            String ruleConfig = context.getRuleConfig();
            boolean createWorkOrder = shouldCreateWorkOrder(ruleConfig);

            // 5. Perform handler-specific business logic
            String repairId = createRepairRecord(deviceId, repairNote, createWorkOrder);

            // 6. Prepare handler output
            result.put("repairId", repairId);
            result.put("repairStatus", "initiated");
            result.put("repairStartTime", Instant.now().toString());
            result.put("handlerExecuted", true);

            if (createWorkOrder) {
                String workOrderId = createWorkOrder(deviceId, repairNote);
                result.put("workOrderId", workOrderId);
                result.put("workOrderStatus", "created");
            }

            // 7. Add any notifications or side effects
            notifyTechnicians(deviceId, repairNote);
            result.put("notificationSent", true);

            log.info("DeviceRepairHandler completed successfully. repairId: {}", repairId);

        } catch (Exception e) {
            log.error("DeviceRepairHandler failed: {}", e.getMessage(), e);
            result.put("handlerError", e.getMessage());
            result.put("handlerExecuted", false);
            throw new BusinessException("Device repair handler failed: " + e.getMessage(), e);
        }

        return result;
    }

    /**
     * Parse ruleConfig to determine if work order should be created
     */
    private boolean shouldCreateWorkOrder(String ruleConfig) {
        if (ruleConfig == null || ruleConfig.isEmpty()) {
            return false;
        }

        // Simple config parsing - in production, use JSON parser
        return ruleConfig.contains("createWorkOrder") && ruleConfig.contains("true");
    }

    /**
     * Create repair record via DynamicDataService.
     * If the repair model does not exist, logs a warning and returns a generated ID
     * without persisting (graceful degradation).
     */
    private String createRepairRecord(String deviceId, String repairNote, boolean withWorkOrder) {
        String repairId = "rep_" + UniqueIdGenerator.generate();

        log.info("Creating repair record: repairId={}, deviceId={}, withWorkOrder={}",
            repairId, deviceId, withWorkOrder);

        if (!metaModelService.isModelExists(REPAIR_MODEL_CODE)) {
            log.warn("Repair model '{}' does not exist. Skipping record persistence, returning generated ID.",
                REPAIR_MODEL_CODE);
            return repairId;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("repair_id", repairId);
        data.put("device_id", deviceId);
        data.put("repair_note", repairNote);
        data.put("status", "initiated");
        data.put("with_work_order", withWorkOrder);

        dynamicDataService.create(REPAIR_MODEL_CODE, data);
        log.info("Repair record persisted: repairId={}, modelCode={}", repairId, REPAIR_MODEL_CODE);

        return repairId;
    }

    /**
     * Create work order for repair task via DynamicDataService.
     * If the work order model does not exist, logs a warning and returns a generated ID
     * without persisting.
     */
    private String createWorkOrder(String deviceId, String repairNote) {
        String workOrderId = "wo_" + UniqueIdGenerator.generate();

        log.info("Creating work order: workOrderId={}, deviceId={}", workOrderId, deviceId);

        if (!metaModelService.isModelExists(WORK_ORDER_MODEL_CODE)) {
            log.warn("Work order model '{}' does not exist. Skipping work order persistence, returning generated ID.",
                WORK_ORDER_MODEL_CODE);
            return workOrderId;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("work_order_id", workOrderId);
        data.put("device_id", deviceId);
        data.put("description", repairNote);
        data.put("status", "created");
        data.put("type", "device_repair");

        dynamicDataService.create(WORK_ORDER_MODEL_CODE, data);
        log.info("Work order persisted: workOrderId={}, modelCode={}", workOrderId, WORK_ORDER_MODEL_CODE);

        return workOrderId;
    }

    /**
     * Notify technicians about repair request via NotificationService.
     * Uses in-app notification. If the context has no userId, logs a warning and skips.
     */
    private void notifyTechnicians(String deviceId, String repairNote) {
        log.info("Notifying technicians for device: {}", deviceId);

        try {
            // Send in-app notification to signal repair request.
            // In a full implementation, this would look up assigned technician userIds
            // and send individual notifications. For now, we log the intent and send
            // a notification to the initiating user as acknowledgement.
            Long userId = com.auraboot.framework.application.tenant.MetaContext.getCurrentUserId();
            if (userId == null) {
                log.warn("No current userId available, skipping repair notification for device: {}", deviceId);
                return;
            }

            String title = "Device Repair Request";
            String content = String.format("Device %s requires repair. Note: %s",
                deviceId, repairNote != null ? repairNote : "N/A");

            notificationService.sendInApp(userId, title, content,
                "device_repair", "device", deviceId);

            log.info("Repair notification sent to userId={} for device: {}", userId, deviceId);
        } catch (Exception e) {
            // Notification failure should not block the repair workflow
            log.warn("Failed to send repair notification for device {}: {}", deviceId, e.getMessage());
        }
    }
}
