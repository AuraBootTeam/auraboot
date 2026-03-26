package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.dto.ExecutionResult;
import com.auraboot.framework.bpm.dto.TriggerConfig;
import com.auraboot.framework.bpm.entity.BpmTriggerDefinition;
import com.auraboot.framework.bpm.enums.TriggerType;
import com.auraboot.framework.bpm.service.TriggerService;
import com.auraboot.framework.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Trigger controller.
 * Provides REST API for managing process triggers.
 */
@Slf4j
@RestController
@RequestMapping("/api/bpm/triggers")
@RequiredArgsConstructor
@Tag(name = "Process Triggers", description = "Trigger management for process orchestration")
public class TriggerController {

    private final TriggerService triggerService;

    @PostMapping
    @Operation(summary = "Create trigger", description = "Create a new process trigger")
    public ApiResponse<BpmTriggerDefinition> createTrigger(@RequestBody CreateTriggerRequest request) {
        BpmTriggerDefinition trigger = triggerService.createTrigger(
                request.processKey(), request.triggerType(), request.config());
        return ApiResponse.success(trigger);
    }

    @GetMapping
    @Operation(summary = "List triggers", description = "List triggers for a process")
    public ApiResponse<List<BpmTriggerDefinition>> listTriggers(@RequestParam String processKey) {
        List<BpmTriggerDefinition> triggers = triggerService.listTriggers(processKey);
        return ApiResponse.success(triggers);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get trigger", description = "Get a specific trigger")
    public ApiResponse<BpmTriggerDefinition> getTrigger(@PathVariable String id) {
        BpmTriggerDefinition trigger = triggerService.getTrigger(id);
        if (trigger == null) {
            return ApiResponse.error("Trigger not found: " + id);
        }
        return ApiResponse.success(trigger);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update trigger", description = "Update trigger configuration")
    public ApiResponse<BpmTriggerDefinition> updateTrigger(
            @PathVariable String id,
            @RequestBody TriggerConfig config) {
        BpmTriggerDefinition trigger = triggerService.updateTrigger(id, config);
        return ApiResponse.success(trigger);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete trigger", description = "Delete a trigger (soft delete)")
    public ApiResponse<Void> deleteTrigger(@PathVariable String id) {
        triggerService.deleteTrigger(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Enable trigger", description = "Enable a trigger")
    public ApiResponse<Void> enableTrigger(@PathVariable String id) {
        triggerService.enableTrigger(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Disable trigger", description = "Disable a trigger")
    public ApiResponse<Void> disableTrigger(@PathVariable String id) {
        triggerService.disableTrigger(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/fire")
    @Operation(summary = "Fire trigger", description = "Manually fire a trigger (for testing)")
    public ApiResponse<ExecutionResult> fireTrigger(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> payload) {
        ExecutionResult result = triggerService.fireTrigger(id, payload);
        return ApiResponse.success(result);
    }

    @PostMapping("/webhook/{triggerId}")
    @Operation(summary = "Webhook trigger", description = "Receive external webhook to fire a trigger")
    public ResponseEntity<Map<String, Object>> webhookTrigger(
            @PathVariable String triggerId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret) {
        BpmTriggerDefinition trigger = triggerService.getTrigger(triggerId);
        if (trigger == null) {
            return ResponseEntity.notFound().build();
        }

        // Validate secret if configured
        if (trigger.getTriggerConfig() != null) {
            String expectedSecret = (String) trigger.getTriggerConfig().get("secret");
            if (expectedSecret != null && !expectedSecret.equals(secret)) {
                return ResponseEntity.status(403).body(Map.of("error", "Invalid webhook secret"));
            }
        }

        ExecutionResult result = triggerService.fireTrigger(triggerId, payload != null ? payload : Map.of());
        return ResponseEntity.ok(Map.of("success", true, "executionId", result.executionId()));
    }

    public record CreateTriggerRequest(
            String processKey,
            TriggerType triggerType,
            TriggerConfig config
    ) {}
}
