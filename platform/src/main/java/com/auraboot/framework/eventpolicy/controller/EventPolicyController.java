package com.auraboot.framework.eventpolicy.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.eventpolicy.dto.EventPolicyDefinitionCopyRequest;
import com.auraboot.framework.eventpolicy.dto.EventPolicyDefinitionCreateRequest;
import com.auraboot.framework.eventpolicy.dto.EventPolicyDefinitionEnabledRequest;
import com.auraboot.framework.eventpolicy.dto.EventPolicyDefinitionSummary;
import com.auraboot.framework.eventpolicy.dto.EventPolicyRunRequest;
import com.auraboot.framework.eventpolicy.dto.EventPolicyVersionCreateRequest;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyDefinitionEntity;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.service.EventPolicyDefinitionService;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
import com.auraboot.framework.eventpolicy.service.EventPolicyVersionService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Event Policy REST API.
 *
 * <p>Base path: {@code /api/event-policy}.
 * Permission module: {@code decision} (see {@link MetaPermission#POLICY_DEFINITION_READ} etc.).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@RestController
@RequestMapping("/api/event-policy")
@RequiredArgsConstructor
@Validated
@Tag(name = "Event Policy", description = "Event policy definition management and runtime evaluation")
public class EventPolicyController {

    private final EventPolicyDefinitionService definitionService;
    private final EventPolicyVersionService versionService;
    private final EventPolicyRuntimeService runtimeService;

    // ==================== Definition CRUD ====================

    @PostMapping("/definitions")
    @Operation(summary = "Create event policy definition")
    @RequirePermission(MetaPermission.POLICY_DEFINITION_MANAGE)
    public ApiResponse<DrtPolicyDefinitionEntity> createDefinition(
            @Valid @RequestBody EventPolicyDefinitionCreateRequest request) {
        log.info("Creating event policy definition: code={}", request.getPolicyCode());
        DrtPolicyDefinitionEntity result = definitionService.create(
                request.getPolicyCode(), request.getPolicyName(),
                request.getEventType(), request.getTargetType(), request.getTargetKey());
        log.info("Event policy definition created: pid={}", result.getPid());
        return ApiResponse.success("Event policy definition created", result);
    }

    @GetMapping("/definitions/{code}")
    @Operation(summary = "Get event policy definition by code")
    @RequirePermission(MetaPermission.POLICY_DEFINITION_READ)
    public ApiResponse<DrtPolicyDefinitionEntity> getDefinitionByCode(
            @Parameter(description = "Policy code") @PathVariable @NotBlank String code) {
        log.info("Getting event policy definition: code={}", code);
        DrtPolicyDefinitionEntity result = definitionService.findByCode(code);
        if (result == null) {
            return ApiResponse.error("Event policy definition not found: " + code);
        }
        return ApiResponse.success(result);
    }

    @PostMapping("/definitions/{code}/enabled")
    @Operation(summary = "Enable or disable event policy definition")
    @RequirePermission(MetaPermission.POLICY_DEFINITION_MANAGE)
    public ApiResponse<DrtPolicyDefinitionEntity> setDefinitionEnabled(
            @Parameter(description = "Policy code") @PathVariable @NotBlank String code,
            @Valid @RequestBody EventPolicyDefinitionEnabledRequest request) {
        log.info("Setting event policy definition enabled: code={}, enabled={}", code, request.getEnabled());
        DrtPolicyDefinitionEntity result = definitionService.setEnabled(code, request.getEnabled());
        return ApiResponse.success(result);
    }

    @PostMapping("/definitions/{code}/copy")
    @Operation(summary = "Copy event policy definition")
    @RequirePermission(MetaPermission.POLICY_DEFINITION_MANAGE)
    public ApiResponse<DrtPolicyDefinitionEntity> copyDefinition(
            @Parameter(description = "Source policy code") @PathVariable @NotBlank String code,
            @Valid @RequestBody EventPolicyDefinitionCopyRequest request) {
        log.info("Copying event policy definition: source={}, copy={}", code, request.getPolicyCode());
        DrtPolicyDefinitionEntity result =
                definitionService.copy(code, request.getPolicyCode(), request.getPolicyName());
        return ApiResponse.success("Event policy definition copied", result);
    }

    @GetMapping("/definitions")
    @Operation(summary = "List event policy definitions")
    @RequirePermission(MetaPermission.POLICY_DEFINITION_READ)
    public ApiResponse<List<EventPolicyDefinitionSummary>> listDefinitions(
            @Parameter(description = "Keyword filter") @RequestParam(required = false) String keyword,
            @Parameter(description = "Event type filter") @RequestParam(required = false) String eventType,
            @Parameter(description = "Target type filter") @RequestParam(required = false) String targetType,
            @Parameter(description = "Target key filter") @RequestParam(required = false) String targetKey,
            @Parameter(description = "Latest version status filter") @RequestParam(required = false) String status) {
        log.info("Listing event policy definitions: keyword={}, eventType={}, targetType={}, targetKey={}, status={}",
                keyword, eventType, targetType, targetKey, status);
        List<EventPolicyDefinitionSummary> result =
                definitionService.listDefinitions(keyword, eventType, targetType, targetKey, status);
        return ApiResponse.success(result);
    }

    // ==================== Version management ====================

    @PostMapping("/definitions/{code}/versions")
    @Operation(summary = "Create draft version",
            description = "Creates a new DRAFT version; version number is auto-incremented")
    @RequirePermission(MetaPermission.POLICY_DEFINITION_MANAGE)
    public ApiResponse<DrtPolicyVersionEntity> createDraftVersion(
            @Parameter(description = "Policy code") @PathVariable @NotBlank String code,
            @Valid @RequestBody EventPolicyVersionCreateRequest request) {
        log.info("Creating draft version: code={}", code);
        DrtPolicyVersionEntity result = versionService.createDraft(
                code,
                request.getPhase(),
                request.getMatchMode(),
                request.getExecutionMode(),
                request.getFailureStrategy(),
                request.getConflictStrategy(),
                request.getDedupStrategy(),
                request.getRulesJson());
        log.info("Draft version created: pid={}, version={}", result.getPid(), result.getVersion());
        return ApiResponse.success("Draft version created", result);
    }

    @GetMapping("/definitions/{code}/versions")
    @Operation(summary = "List versions for an event policy")
    @RequirePermission(MetaPermission.POLICY_DEFINITION_READ)
    public ApiResponse<List<DrtPolicyVersionEntity>> listVersions(
            @Parameter(description = "Policy code") @PathVariable @NotBlank String code) {
        log.info("Listing versions: code={}", code);
        List<DrtPolicyVersionEntity> result = versionService.listByCode(code);
        return ApiResponse.success(result);
    }

    @PostMapping("/versions/{pid}/validate")
    @Operation(summary = "Validate a draft version",
            description = "Runs structural validation; on success transitions to VALIDATED")
    @RequirePermission(MetaPermission.POLICY_DEFINITION_MANAGE)
    public ApiResponse<DrtPolicyVersionEntity> validateVersion(
            @Parameter(description = "Version PID") @PathVariable @NotBlank String pid) {
        log.info("Validating version: pid={}", pid);
        DrtPolicyVersionEntity result = versionService.validate(pid);
        return ApiResponse.success(result);
    }

    @PostMapping("/versions/{pid}/publish")
    @Operation(summary = "Publish a validated version",
            description = "Transitions from VALIDATED to PUBLISHED (immutable from this point)")
    @RequirePermission(MetaPermission.POLICY_DEFINITION_PUBLISH)
    public ApiResponse<DrtPolicyVersionEntity> publishVersion(
            @Parameter(description = "Version PID") @PathVariable @NotBlank String pid) {
        log.info("Publishing version: pid={}", pid);
        DrtPolicyVersionEntity result = versionService.publish(pid);
        log.info("Version published: pid={}, policyCode={}, version={}",
                pid, result.getPolicyCode(), result.getVersion());
        return ApiResponse.success("Version published", result);
    }

    // ==================== Runtime ====================

    @PostMapping("/run")
    @Operation(summary = "Evaluate the published event policy",
            description = "Resolves the published policy for (eventType, targetType, targetKey), " +
                    "evaluates all rules, and returns resolved action plans. No execution happens here.")
    @RequirePermission(MetaPermission.POLICY_RUNTIME_RUN)
    public ApiResponse<EventPolicyResult> run(
            @Valid @RequestBody EventPolicyRunRequest request) {
        log.info("Running event policy: eventType={}, targetType={}, targetKey={}",
                request.getEventType(), request.getTargetType(), request.getTargetKey());
        Map<String, Map<String, Object>> context =
                request.getContext() != null ? request.getContext() : Map.of();
        EventPolicyResult result = runtimeService.run(
                request.getEventType(), request.getTargetType(), request.getTargetKey(), context);
        log.info("Event policy run complete: status={}", result.status());
        return ApiResponse.success(result);
    }

    @PostMapping("/run-and-execute")
    @Operation(summary = "Run and execute the published event policy",
            description = "End-to-end: resolves the policy, evaluates rules, then executes the resolved "
                    + "action plans via the PolicyExecutor (ordered, idempotent). Returns both outcomes.")
    @RequirePermission(MetaPermission.POLICY_RUNTIME_RUN)
    public ApiResponse<com.auraboot.framework.eventpolicy.model.EventPolicyExecutionResult> runAndExecute(
            @Valid @RequestBody EventPolicyRunRequest request) {
        log.info("Run+execute event policy: eventType={}, targetType={}, targetKey={}",
                request.getEventType(), request.getTargetType(), request.getTargetKey());
        Map<String, Map<String, Object>> context =
                request.getContext() != null ? request.getContext() : Map.of();
        var result = runtimeService.runAndExecute(
                request.getEventType(), request.getTargetType(), request.getTargetKey(), context);
        return ApiResponse.success(result);
    }
}
