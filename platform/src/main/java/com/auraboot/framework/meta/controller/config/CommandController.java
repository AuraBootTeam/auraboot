package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.annotation.Idempotent;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.CommandAuditLogService;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * Command Controller
 * RESTful API for managing command definitions and executing commands.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/commands")
@RequiredArgsConstructor
@Validated
@Tag(name = "Commands", description = "Command definition management and command execution APIs")
public class CommandController {

    private final CommandService commandService;
    private final CommandExecutor commandExecutor;
    private final PluginResourceTracker pluginResourceTracker;
    private final CommandAuditLogService commandAuditLogService;

    // ==================== CRUD ====================

    @PostMapping
    @RequirePermission(MetaPermission.COMMAND_MANAGE)
    public ApiResponse<CommandDefinitionDTO> create(@Valid @RequestBody CommandDefinitionCreateRequest request) {
        CommandDefinitionDTO result = commandService.create(request);
        return ApiResponse.success(result);
    }

    @PutMapping("/{pid}")
    @RequirePermission(MetaPermission.COMMAND_MANAGE)
    public ApiResponse<CommandDefinitionDTO> update(
            @PathVariable String pid,
            @Valid @RequestBody CommandDefinitionCreateRequest request) {
        CommandDefinitionDTO result = commandService.update(pid, request);
        pluginResourceTracker.markAsUserModified(ResourceType.COMMAND, result.getCode());
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{pid}")
    @RequirePermission(MetaPermission.COMMAND_MANAGE)
    public ApiResponse<Void> delete(@PathVariable String pid) {
        CommandDefinitionDTO existing = commandService.findByPid(pid);
        if (existing != null) {
            pluginResourceTracker.markAsUserModified(ResourceType.COMMAND, existing.getCode());
        }
        commandService.delete(pid);
        return ApiResponse.success();
    }

    @GetMapping("/{pid}")
    @RequirePermission(MetaPermission.COMMAND_READ)
    public ApiResponse<CommandDefinitionDTO> findByPid(@PathVariable String pid) {
        CommandDefinitionDTO result = commandService.findByPid(pid);
        return ApiResponse.success(result);
    }

    @GetMapping("/by-code/{code}")
    @RequirePermission(MetaPermission.COMMAND_READ)
    public ApiResponse<CommandDefinitionDTO> findByCode(@PathVariable String code) {
        CommandDefinitionDTO result = commandService.findByCode(code);
        return ApiResponse.success(result);
    }

    @GetMapping
    @RequirePermission(MetaPermission.COMMAND_READ)
    public ApiResponse<List<CommandDefinitionDTO>> listByModelCode(@RequestParam String modelCode) {
        List<CommandDefinitionDTO> result = commandService.listByModelCode(modelCode);
        return ApiResponse.success(result);
    }

    // ==================== Publish ====================

    @PostMapping("/{pid}/publish")
    @RequirePermission(MetaPermission.COMMAND_MANAGE)
    public ApiResponse<CommandDefinitionDTO> publish(@PathVariable String pid) {
        CommandDefinitionDTO result = commandService.publish(pid);
        return ApiResponse.success(result);
    }

    // ==================== Binding Rules ====================

    @PostMapping("/{pid}/binding-rules")
    @RequirePermission(MetaPermission.COMMAND_MANAGE)
    public ApiResponse<BindingRuleDTO> addBindingRule(
            @PathVariable String pid,
            @Valid @RequestBody BindingRuleDTO rule) {
        BindingRuleDTO result = commandService.addBindingRule(pid, rule);
        return ApiResponse.success(result);
    }

    @GetMapping("/{pid}/binding-rules")
    @RequirePermission(MetaPermission.COMMAND_READ)
    public ApiResponse<List<BindingRuleDTO>> getBindingRules(@PathVariable String pid) {
        List<BindingRuleDTO> result = commandService.getBindingRules(pid);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/binding-rules/{rulePid}")
    @RequirePermission(MetaPermission.COMMAND_MANAGE)
    public ApiResponse<Void> removeBindingRule(@PathVariable String rulePid) {
        commandService.removeBindingRule(rulePid);
        return ApiResponse.success();
    }

    @PostMapping("/{pid}/binding-rules/reorder")
    @RequirePermission(MetaPermission.COMMAND_MANAGE)
    public ApiResponse<Void> reorderBindingRules(
            @PathVariable String pid,
            @RequestBody List<String> rulePids) {
        commandService.reorderBindingRules(pid, rulePids);
        return ApiResponse.success();
    }

    // ==================== Execute ====================

    @PostMapping("/execute/{commandCode}")
    @RequirePermission(MetaPermission.COMMAND_EXECUTE)
    @Idempotent(keyExpression = "#request.clientRequestId != null ? #commandCode + ':' + #request.clientRequestId : null", ttl = 86400)
    @Operation(summary = "Execute a command", description = "Execute a DSL command by code. The payload is passed as the command's input data. Supports idempotency via clientRequestId.")
    public ApiResponse<CommandExecuteResult> execute(
            @PathVariable String commandCode,
            @Valid @RequestBody CommandExecuteRequest request) {
        CommandExecuteResult result = commandExecutor.execute(commandCode, request);
        return ApiResponse.success(result);
    }

    // ==================== Audit Logs ====================

    @GetMapping("/audit-logs")
    @RequirePermission(MetaPermission.COMMAND_READ)
    public ApiResponse<PaginationResult<CommandAuditLogDTO>> queryAuditLogs(
            @RequestParam(required = false) String commandCode,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        PaginationResult<CommandAuditLogDTO> result =
                commandAuditLogService.queryLogs(commandCode, success, startDate, endDate, pageNum, pageSize);
        return ApiResponse.success(result);
    }

    @GetMapping("/audit-logs/{id}")
    @RequirePermission(MetaPermission.COMMAND_READ)
    public ApiResponse<CommandAuditLogDTO> getAuditLog(@PathVariable Long id) {
        CommandAuditLogDTO result = commandAuditLogService.findById(id);
        return ApiResponse.success(result);
    }
}
