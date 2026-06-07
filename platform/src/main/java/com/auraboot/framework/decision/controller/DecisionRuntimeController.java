package com.auraboot.framework.decision.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtDefinitionDTO;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.dto.DrtLogDTO;
import com.auraboot.framework.decision.dto.DrtTestRunRequest;
import com.auraboot.framework.decision.dto.DrtValidateRequest;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.service.DecisionDefinitionService;
import com.auraboot.framework.decision.service.DecisionEvaluationService;
import com.auraboot.framework.decision.service.DecisionVersionService;
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

/**
 * Decision Runtime REST API.
 *
 * <p>Base path: {@code /api/decision}  (NOT /api/meta/decisions — that is the adjudication module).
 * Permission module: {@code decision} (see {@link MetaPermission#DRT_DEFINITION_READ} etc.).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@RestController
@RequestMapping("/api/decision")
@RequiredArgsConstructor
@Validated
@Tag(name = "Decision Runtime", description = "Decision definition management and runtime evaluation")
public class DecisionRuntimeController {

    private final DecisionDefinitionService definitionService;
    private final DecisionVersionService versionService;
    private final DecisionEvaluationService evaluationService;

    // ==================== Stateless validation + evaluation ====================

    @PostMapping("/validate")
    @Operation(summary = "Validate decision content",
            description = "Stateless structural validation — no persisted version required")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<DecisionValidateResult> validate(
            @Valid @RequestBody DrtValidateRequest request) {
        log.info("Decision validate: kind={}", request.getKind());
        DecisionValidateResult result = evaluationService.validate(request);
        return ApiResponse.success(result);
    }

    @PostMapping("/test-run")
    @Operation(summary = "Test-run decision content",
            description = "In-memory evaluation of draft content without persistence")
    @RequirePermission(MetaPermission.DRT_RUNTIME_EVALUATE)
    public ApiResponse<DecisionResult> testRun(
            @Valid @RequestBody DrtTestRunRequest request) {
        log.info("Decision test-run: kind={}, adapter={}", request.getKind(), request.getRuntimeAdapter());
        DecisionResult result = evaluationService.testRun(request);
        return ApiResponse.success(result);
    }

    @PostMapping("/evaluate")
    @Operation(summary = "Evaluate a published decision",
            description = "Authoritative evaluation; writes an audit log entry")
    @RequirePermission(MetaPermission.DRT_RUNTIME_EVALUATE)
    public ApiResponse<DecisionResult> evaluate(
            @Valid @RequestBody DrtEvaluateRequest request) {
        log.info("Decision evaluate: code={}, binding={}", request.getDecisionCode(), request.getBinding());
        DecisionResult result = evaluationService.evaluate(request);
        return ApiResponse.success(result);
    }

    // ==================== Definition CRUD ====================

    @PostMapping("/definitions")
    @Operation(summary = "Create decision definition")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<DrtDefinitionDTO> createDefinition(
            @Valid @RequestBody DrtDefinitionCreateRequest request) {
        log.info("Creating decision definition: code={}", request.getDecisionCode());
        DrtDefinitionDTO result = definitionService.create(request);
        log.info("Decision definition created: pid={}", result.getPid());
        return ApiResponse.success("Decision definition created", result);
    }

    @GetMapping("/definitions/{code}")
    @Operation(summary = "Get decision definition by code")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<DrtDefinitionDTO> getDefinitionByCode(
            @Parameter(description = "Decision code") @PathVariable @NotBlank String code) {
        log.info("Getting decision definition: code={}", code);
        DrtDefinitionDTO result = definitionService.findByCode(code);
        if (result == null) {
            return ApiResponse.error("Decision definition not found: " + code);
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/definitions")
    @Operation(summary = "List decision definitions")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<PageResult<DrtDefinitionDTO>> listDefinitions(
            @Parameter(description = "Search keyword") @RequestParam(required = false) String keyword,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        log.info("Listing decision definitions: keyword={}, page={}, size={}", keyword, page, size);
        PageResult<DrtDefinitionDTO> result = definitionService.list(keyword, page, size);
        return ApiResponse.success(result);
    }

    @PutMapping("/definitions/{code}")
    @Operation(summary = "Update decision definition")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<DrtDefinitionDTO> updateDefinition(
            @Parameter(description = "Decision code") @PathVariable @NotBlank String code,
            @Valid @RequestBody DrtDefinitionCreateRequest request) {
        log.info("Updating decision definition: code={}", code);
        // Look up by code, then delegate to update(pid, ...)
        DrtDefinitionDTO existing = definitionService.findByCode(code);
        if (existing == null) {
            return ApiResponse.error("Decision definition not found: " + code);
        }
        DrtDefinitionDTO result = definitionService.update(existing.getPid(), request);
        return ApiResponse.success("Decision definition updated", result);
    }

    // ==================== Version management ====================

    @PostMapping("/definitions/{code}/versions")
    @Operation(summary = "Create draft version",
            description = "Creates a new DRAFT version; version number is auto-incremented")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<DrtVersionDTO> createDraftVersion(
            @Parameter(description = "Decision code") @PathVariable @NotBlank String code,
            @Valid @RequestBody DrtVersionCreateRequest request) {
        log.info("Creating draft version: code={}", code);
        DrtVersionDTO result = versionService.createDraft(code, request);
        log.info("Draft version created: pid={}, version={}", result.getPid(), result.getVersion());
        return ApiResponse.success("Draft version created", result);
    }

    @GetMapping("/definitions/{code}/versions")
    @Operation(summary = "List versions for a decision")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<List<DrtVersionDTO>> listVersions(
            @Parameter(description = "Decision code") @PathVariable @NotBlank String code) {
        log.info("Listing versions: code={}", code);
        List<DrtVersionDTO> result = versionService.listByCode(code);
        return ApiResponse.success(result);
    }

    @PostMapping("/versions/{pid}/validate")
    @Operation(summary = "Validate a draft version",
            description = "Runs structural validation; on success transitions to VALIDATED")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<DecisionValidateResult> validateVersion(
            @Parameter(description = "Version PID") @PathVariable @NotBlank String pid) {
        log.info("Validating version: pid={}", pid);
        DecisionValidateResult result = versionService.validate(pid);
        return ApiResponse.success(result);
    }

    @PostMapping("/versions/{pid}/publish")
    @Operation(summary = "Publish a validated version",
            description = "Transitions from VALIDATED to PUBLISHED (immutable from this point)")
    @RequirePermission(MetaPermission.DRT_DEFINITION_PUBLISH)
    public ApiResponse<DrtVersionDTO> publishVersion(
            @Parameter(description = "Version PID") @PathVariable @NotBlank String pid) {
        log.info("Publishing version: pid={}", pid);
        DrtVersionDTO result = versionService.publish(pid);
        log.info("Version published: pid={}, code={}, version={}",
                pid, result.getDecisionCode(), result.getVersion());
        return ApiResponse.success("Version published", result);
    }

    @GetMapping("/versions/{pid}")
    @Operation(summary = "Get version by PID")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<DrtVersionDTO> getVersion(
            @Parameter(description = "Version PID") @PathVariable @NotBlank String pid) {
        log.info("Getting version: pid={}", pid);
        DrtVersionDTO result = versionService.findByPid(pid);
        if (result == null) {
            return ApiResponse.error("Decision version not found: " + pid);
        }
        return ApiResponse.success(result);
    }

    // ==================== Logs ====================

    @GetMapping("/logs")
    @Operation(summary = "Query evaluation logs by trace ID")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<List<DrtLogDTO>> getLogs(
            @Parameter(description = "Trace ID") @RequestParam @NotBlank String traceId) {
        log.info("Getting decision logs: traceId={}", traceId);
        List<DrtLogDTO> result = evaluationService.findLogsByTraceId(traceId);
        return ApiResponse.success(result);
    }
}
