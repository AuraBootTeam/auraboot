package com.auraboot.framework.decision.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.decision.dto.ConditionFragmentCreateRequest;
import com.auraboot.framework.decision.dto.ConditionFragmentDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentEvaluateRequest;
import com.auraboot.framework.decision.dto.ConditionFragmentEvaluationDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentImpactDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentVersionCreateRequest;
import com.auraboot.framework.decision.dto.DecisionActionCatalogDTO;
import com.auraboot.framework.decision.dto.DecisionDashboardDTO;
import com.auraboot.framework.decision.dto.DecisionFieldImpactDTO;
import com.auraboot.framework.decision.dto.DecisionFieldPreflightDTO;
import com.auraboot.framework.decision.dto.DecisionFieldPreflightRequest;
import com.auraboot.framework.decision.dto.DecisionFactCatalogDTO;
import com.auraboot.framework.decision.dto.DecisionImpactDTO;
import com.auraboot.framework.decision.dto.DecisionIntegrationImpactDTO;
import com.auraboot.framework.decision.dto.DecisionModelFieldDTO;
import com.auraboot.framework.decision.dto.DecisionPermissionMatrixDTO;
import com.auraboot.framework.decision.dto.DecisionRolloutActionRequest;
import com.auraboot.framework.decision.dto.DecisionRolloutCreateRequest;
import com.auraboot.framework.decision.dto.DecisionRolloutDTO;
import com.auraboot.framework.decision.dto.DecisionRolloutMetricsDTO;
import com.auraboot.framework.decision.dto.DecisionTableAnalysisDTO;
import com.auraboot.framework.decision.dto.DecisionTableAnalyzeRequest;
import com.auraboot.framework.decision.dto.DecisionTableDmnXmlDTO;
import com.auraboot.framework.decision.dto.DecisionTableDmnXmlRequest;
import com.auraboot.framework.decision.dto.DecisionUsageIndexRebuildDTO;
import com.auraboot.framework.decision.dto.DecisionVersionTransitionRequest;
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
import com.auraboot.framework.decision.service.ConditionFragmentService;
import com.auraboot.framework.decision.service.DecisionActionCatalogService;
import com.auraboot.framework.decision.service.DecisionDashboardService;
import com.auraboot.framework.decision.service.DecisionImpactService;
import com.auraboot.framework.decision.service.DecisionModelFieldService;
import com.auraboot.framework.decision.service.DecisionPermissionMatrixService;
import com.auraboot.framework.decision.service.DecisionRolloutService;
import com.auraboot.framework.decision.service.DecisionTableAnalysisService;
import com.auraboot.framework.decision.service.DecisionTableDmnXmlService;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import com.auraboot.framework.decision.service.DrtDefinitionService;
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

    private final DrtDefinitionService definitionService;
    private final DecisionVersionService versionService;
    private final DecisionEvaluationService evaluationService;
    private final DecisionDashboardService dashboardService;
    private final DecisionModelFieldService modelFieldService;
    private final DecisionPermissionMatrixService permissionMatrixService;
    private final DecisionImpactService impactService;
    private final DecisionRolloutService rolloutService;
    private final DecisionUsageIndexService usageIndexService;
    private final DecisionTableAnalysisService tableAnalysisService;
    private final DecisionTableDmnXmlService tableDmnXmlService;
    private final ConditionFragmentService conditionFragmentService;
    private final DecisionActionCatalogService actionCatalogService;

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

    @PostMapping("/tables/analyze")
    @Operation(summary = "Analyze a decision table",
            description = "Stateless DMN table analysis for finite-domain gaps, overlaps, conflicts, and unreachable rules.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<DecisionTableAnalysisDTO> analyzeTable(
            @Valid @RequestBody DecisionTableAnalyzeRequest request) {
        log.info("Decision table analyze: decisionCode={}, versionPid={}",
                request.getDecisionCode(), request.getVersionPid());
        return ApiResponse.success(tableAnalysisService.analyze(request.getModel()));
    }

    @PostMapping("/tables/export-dmn")
    @Operation(summary = "Export a visual decision table to DMN XML",
            description = "Converts the editable table model to a KIE-compilable OMG DMN decisionTable XML subset.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<DecisionTableDmnXmlDTO> exportDecisionTableDmn(
            @Valid @RequestBody DecisionTableDmnXmlRequest request) {
        log.info("Decision table export DMN: decisionName={}", request.getDecisionName());
        return ApiResponse.success(tableDmnXmlService.exportDmn(request));
    }

    @PostMapping("/tables/import-dmn")
    @Operation(summary = "Import DMN XML into the visual decision-table model",
            description = "Imports the platform-supported DMN decisionTable subset back into the editor model.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<DecisionTableDmnXmlDTO> importDecisionTableDmn(
            @Valid @RequestBody DecisionTableDmnXmlRequest request) {
        log.info("Decision table import DMN");
        return ApiResponse.success(tableDmnXmlService.importDmn(request));
    }

    @PostMapping("/tables/round-trip")
    @Operation(summary = "Round-trip verify a visual decision table through DMN XML",
            description = "Exports the table to DMN XML, validates it with the Drools DMN adapter, then imports it back.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<DecisionTableDmnXmlDTO> roundTripDecisionTableDmn(
            @Valid @RequestBody DecisionTableDmnXmlRequest request) {
        log.info("Decision table DMN round-trip: decisionName={}", request.getDecisionName());
        return ApiResponse.success(tableDmnXmlService.roundTrip(request));
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

    @PostMapping("/batch-evaluate")
    @Operation(summary = "Batch-evaluate decisions",
            description = "Evaluate many decisions in one call (SLA scheduler / bulk import). Each "
                    + "entry is independent — a failing entry yields an ERROR result, not a failed batch.")
    @RequirePermission(MetaPermission.DRT_RUNTIME_EVALUATE)
    public ApiResponse<java.util.List<DecisionResult>> batchEvaluate(
            @Valid @RequestBody java.util.List<DrtEvaluateRequest> requests) {
        log.info("Decision batch-evaluate: {} entries", requests == null ? 0 : requests.size());
        return ApiResponse.success(evaluationService.batchEvaluate(requests));
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

    @GetMapping("/definitions/{code}/impact")
    @Operation(summary = "Get decision impact graph",
            description = "Returns downstream consumers and outgoing field/function/sub-decision references.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<DecisionImpactDTO> getDecisionImpact(
            @Parameter(description = "Decision code") @PathVariable @NotBlank String code) {
        log.info("Getting decision impact: code={}", code);
        return ApiResponse.success(impactService.getDecisionImpact(code));
    }

    @PostMapping("/definitions/{code}/rollouts")
    @Operation(summary = "Create decision rollout policy")
    @RequirePermission(MetaPermission.DRT_ROLLOUT_MANAGE)
    public ApiResponse<DecisionRolloutDTO> createRollout(
            @Parameter(description = "Decision code") @PathVariable @NotBlank String code,
            @Valid @RequestBody DecisionRolloutCreateRequest request) {
        log.info("Creating decision rollout: code={}, baseline={}, candidate={}, pct={}",
                code, request.getBaselineVersion(), request.getCandidateVersion(), request.getPercentage());
        return ApiResponse.success("Decision rollout created", rolloutService.create(code, request));
    }

    @GetMapping("/definitions/{code}/rollouts")
    @Operation(summary = "List decision rollout policies")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<List<DecisionRolloutDTO>> listRollouts(
            @Parameter(description = "Decision code") @PathVariable @NotBlank String code) {
        log.info("Listing decision rollouts: code={}", code);
        return ApiResponse.success(rolloutService.list(code));
    }

    @GetMapping("/definitions/{code}/rollouts/active")
    @Operation(summary = "Get active decision rollout policy")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<DecisionRolloutDTO> getActiveRollout(
            @Parameter(description = "Decision code") @PathVariable @NotBlank String code) {
        log.info("Getting active decision rollout: code={}", code);
        return ApiResponse.success(rolloutService.active(code));
    }

    @GetMapping("/rollouts")
    @Operation(summary = "List decision rollout policies for DSL API-backed pages")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<PageResult<DecisionRolloutDTO>> listRolloutPolicies(
            @Parameter(description = "Decision code filter") @RequestParam(required = false) String decisionCode,
            @Parameter(description = "Rollout status filter") @RequestParam(required = false) String status,
            @Parameter(description = "Keyword search") @RequestParam(required = false) String keyword,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort field") @RequestParam(required = false) String sortField,
            @Parameter(description = "Sort order") @RequestParam(required = false) String sortOrder) {
        log.info("Listing rollout policies: decisionCode={}, status={}, keyword={}, page={}, size={}",
                decisionCode, status, keyword, page, size);
        return ApiResponse.success(rolloutService.listPage(
                decisionCode, status, keyword, page, size, sortField, sortOrder));
    }

    @GetMapping("/rollouts/{pid}")
    @Operation(summary = "Get decision rollout policy detail")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<DecisionRolloutDTO> getRolloutPolicy(
            @Parameter(description = "Rollout policy PID") @PathVariable @NotBlank String pid) {
        log.info("Getting decision rollout policy: pid={}", pid);
        return ApiResponse.success(rolloutService.get(pid));
    }

    @PostMapping("/rollouts/{pid}/activate")
    @Operation(summary = "Activate decision rollout policy")
    @RequirePermission(MetaPermission.DRT_ROLLOUT_MANAGE)
    public ApiResponse<DecisionRolloutDTO> activateRollout(
            @Parameter(description = "Rollout policy PID") @PathVariable @NotBlank String pid,
            @RequestBody(required = false) DecisionRolloutActionRequest request) {
        log.info("Activating decision rollout: pid={}", pid);
        return ApiResponse.success("Decision rollout activated", rolloutService.activate(pid, request));
    }

    @PostMapping("/rollouts/{pid}/pause")
    @Operation(summary = "Pause decision rollout policy")
    @RequirePermission(MetaPermission.DRT_ROLLOUT_MANAGE)
    public ApiResponse<DecisionRolloutDTO> pauseRollout(
            @Parameter(description = "Rollout policy PID") @PathVariable @NotBlank String pid,
            @RequestBody(required = false) DecisionRolloutActionRequest request) {
        log.info("Pausing decision rollout: pid={}", pid);
        return ApiResponse.success("Decision rollout paused", rolloutService.pause(pid, request));
    }

    @PostMapping("/rollouts/{pid}/promote")
    @Operation(summary = "Promote decision rollout policy")
    @RequirePermission(MetaPermission.DRT_ROLLOUT_PROMOTE)
    public ApiResponse<DecisionRolloutDTO> promoteRollout(
            @Parameter(description = "Rollout policy PID") @PathVariable @NotBlank String pid,
            @RequestBody(required = false) DecisionRolloutActionRequest request) {
        log.info("Promoting decision rollout: pid={}", pid);
        return ApiResponse.success("Decision rollout promoted", rolloutService.promote(pid, request));
    }

    @PostMapping("/rollouts/{pid}/rollback")
    @Operation(summary = "Rollback decision rollout policy")
    @RequirePermission(MetaPermission.DRT_ROLLOUT_ROLLBACK)
    public ApiResponse<DecisionRolloutDTO> rollbackRollout(
            @Parameter(description = "Rollout policy PID") @PathVariable @NotBlank String pid,
            @RequestBody(required = false) DecisionRolloutActionRequest request) {
        log.info("Rolling back decision rollout: pid={}", pid);
        return ApiResponse.success("Decision rollout rolled back", rolloutService.rollback(pid, request));
    }

    @GetMapping("/rollouts/{pid}/metrics")
    @Operation(summary = "Get decision rollout metrics")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<DecisionRolloutMetricsDTO> getRolloutMetrics(
            @Parameter(description = "Rollout policy PID") @PathVariable @NotBlank String pid,
            @Parameter(description = "Metrics window in hours, capped at 2160")
            @RequestParam(defaultValue = "168") int windowHours,
            @Parameter(description = "Metrics bucket size in minutes")
            @RequestParam(defaultValue = "60") int bucketMinutes,
            @Parameter(description = "Refresh pre-aggregated buckets from evaluation logs before reading")
            @RequestParam(defaultValue = "true") boolean refresh) {
        log.info("Getting decision rollout metrics: pid={}, windowHours={}, bucketMinutes={}, refresh={}",
                pid, windowHours, bucketMinutes, refresh);
        return ApiResponse.success(rolloutService.metrics(pid, windowHours, bucketMinutes, refresh));
    }

    @GetMapping("/fields/impact")
    @Operation(summary = "Get field impact graph",
            description = "Returns decision versions and other indexed references that read a field.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<DecisionFieldImpactDTO> getFieldImpact(
            @Parameter(description = "Field reference, e.g. record.data.amount")
            @RequestParam @NotBlank String fieldRef) {
        log.info("Getting field impact: fieldRef={}", fieldRef);
        return ApiResponse.success(impactService.getFieldImpact(fieldRef));
    }

    @PostMapping("/fields/preflight")
    @Operation(summary = "Preflight a field/schema change",
            description = "Checks whether deleting a field or changing its data type would affect indexed decisions.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<DecisionFieldPreflightDTO> preflightFieldChange(
            @Valid @RequestBody DecisionFieldPreflightRequest request) {
        log.info("Preflighting field change: fieldRef={}, action={}", request.getFieldRef(), request.getAction());
        return ApiResponse.success(impactService.preflightFieldChange(request));
    }

    @GetMapping("/integrations/impact")
    @Operation(summary = "Get integration impact graph",
            description = "Returns sources that reference a platform API connector or webhook subscription.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<DecisionIntegrationImpactDTO> getIntegrationImpact(
            @Parameter(description = "Target type: CONNECTOR or WEBHOOK")
            @RequestParam @NotBlank String targetType,
            @Parameter(description = "Connector/Webhook PID or indexed target code")
            @RequestParam @NotBlank String targetCode) {
        log.info("Getting integration impact: targetType={}, targetCode={}", targetType, targetCode);
        return ApiResponse.success(impactService.getIntegrationImpact(targetType, targetCode));
    }

    @PostMapping("/condition-fragments")
    @Operation(summary = "Create reusable condition fragment",
            description = "Persists a tenant-scoped ConditionSpec fragment that SLA, BPM, Automation, Permission and EventPolicy can reuse.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<ConditionFragmentDTO> createConditionFragment(
            @Valid @RequestBody ConditionFragmentCreateRequest request) {
        log.info("Creating condition fragment: code={}", request.getFragmentCode());
        return ApiResponse.success("Condition fragment created", conditionFragmentService.create(request));
    }

    @PostMapping("/condition-fragments/{code}/versions")
    @Operation(summary = "Create next condition-fragment draft version",
            description = "Creates a new DRAFT version from an immutable published/deprecated/retired fragment.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<ConditionFragmentDTO> createConditionFragmentVersion(
            @Parameter(description = "Condition fragment code") @PathVariable @NotBlank String code,
            @Valid @RequestBody ConditionFragmentVersionCreateRequest request) {
        return ApiResponse.success("Condition fragment draft version created",
                conditionFragmentService.createVersion(code, request));
    }

    @GetMapping("/condition-fragments")
    @Operation(summary = "List reusable condition fragments")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<PageResult<ConditionFragmentDTO>> listConditionFragments(
            @Parameter(description = "Search keyword") @RequestParam(required = false) String keyword,
            @Parameter(description = "Scope type filter") @RequestParam(required = false) String scopeType,
            @Parameter(description = "Scope ref filter") @RequestParam(required = false) String scopeRef,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(conditionFragmentService.list(keyword, scopeType, scopeRef, page, size));
    }

    @GetMapping("/condition-fragments/{code}")
    @Operation(summary = "Get reusable condition fragment by code")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<ConditionFragmentDTO> getConditionFragment(
            @Parameter(description = "Condition fragment code") @PathVariable @NotBlank String code) {
        return ApiResponse.success(conditionFragmentService.findByCode(code));
    }

    @GetMapping("/condition-fragments/{code}/versions")
    @Operation(summary = "List condition-fragment versions")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<List<ConditionFragmentDTO>> listConditionFragmentVersions(
            @Parameter(description = "Condition fragment code") @PathVariable @NotBlank String code) {
        return ApiResponse.success(conditionFragmentService.listVersions(code));
    }

    @PostMapping("/condition-fragments/{code}/evaluate")
    @Operation(summary = "Evaluate reusable condition fragment",
            description = "Runs a persisted condition fragment with the same Condition AST evaluator used by Decision Runtime.")
    @RequirePermission(MetaPermission.DRT_RUNTIME_EVALUATE)
    public ApiResponse<ConditionFragmentEvaluationDTO> evaluateConditionFragment(
            @Parameter(description = "Condition fragment code") @PathVariable @NotBlank String code,
            @RequestBody(required = false) ConditionFragmentEvaluateRequest request) {
        return ApiResponse.success(conditionFragmentService.evaluate(code, request));
    }

    @GetMapping("/condition-fragments/{code}/impact")
    @Operation(summary = "Get condition fragment impact graph",
            description = "Returns consumers that reuse the condition fragment.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<ConditionFragmentImpactDTO> getConditionFragmentImpact(
            @Parameter(description = "Condition fragment code") @PathVariable @NotBlank String code) {
        return ApiResponse.success(conditionFragmentService.impact(code));
    }

    @PostMapping("/condition-fragment-versions/{pid}/validate")
    @Operation(summary = "Validate a condition-fragment draft version")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<ConditionFragmentDTO> validateConditionFragmentVersion(
            @Parameter(description = "Condition fragment version PID") @PathVariable @NotBlank String pid) {
        return ApiResponse.success(conditionFragmentService.validate(pid));
    }

    @PostMapping("/condition-fragment-versions/{pid}/publish")
    @Operation(summary = "Publish a validated condition-fragment version")
    @RequirePermission(MetaPermission.DRT_DEFINITION_PUBLISH)
    public ApiResponse<ConditionFragmentDTO> publishConditionFragmentVersion(
            @Parameter(description = "Condition fragment version PID") @PathVariable @NotBlank String pid,
            @RequestBody(required = false) DecisionVersionTransitionRequest request) {
        return ApiResponse.success("Condition fragment version published",
                conditionFragmentService.publish(pid, impactAcknowledged(request)));
    }

    @PostMapping("/usage-index/rebuild")
    @Operation(summary = "Rebuild decision usage index",
            description = "Rebuilds the tenant-scoped Decision Runtime usage index from authoritative sources.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<DecisionUsageIndexRebuildDTO> rebuildUsageIndex() {
        log.info("Rebuilding decision usage index");
        return ApiResponse.success(impactService.rebuildUsageIndex());
    }

    @PostMapping("/usage-index/sources/{sourceType}/{sourcePid}/refresh")
    @Operation(summary = "Refresh a single usage-index source",
            description = "Replaces indexed refs for one source without rebuilding the whole tenant index.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<DecisionUsageIndexRebuildDTO> refreshUsageIndexSource(
            @Parameter(description = "Source type: DECISION_VERSION, AUTOMATION, SLA_RULE, EVENT_POLICY")
            @PathVariable @NotBlank String sourceType,
            @Parameter(description = "Source PID") @PathVariable @NotBlank String sourcePid) {
        log.info("Refreshing decision usage-index source: type={}, pid={}", sourceType, sourcePid);
        return ApiResponse.success(usageIndexService.refreshSource(sourceType, sourcePid));
    }

    @DeleteMapping("/usage-index/sources/{sourceType}/{sourcePid}")
    @Operation(summary = "Delete refs for a usage-index source",
            description = "Removes stale refs for a deleted or archived source without rebuilding the whole tenant index.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<DecisionUsageIndexRebuildDTO> deleteUsageIndexSource(
            @Parameter(description = "Source type: DECISION_VERSION, AUTOMATION, SLA_RULE, EVENT_POLICY")
            @PathVariable @NotBlank String sourceType,
            @Parameter(description = "Source PID") @PathVariable @NotBlank String sourcePid) {
        log.info("Deleting decision usage-index source refs: type={}, pid={}", sourceType, sourcePid);
        return ApiResponse.success("Decision usage-index source refs deleted",
                usageIndexService.deleteSource(sourceType, sourcePid));
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
            @Parameter(description = "Version PID") @PathVariable @NotBlank String pid,
            @RequestBody(required = false) DecisionVersionTransitionRequest request) {
        log.info("Publishing version: pid={}", pid);
        DrtVersionDTO result = versionService.publish(pid, impactAcknowledged(request));
        log.info("Version published: pid={}, code={}, version={}",
                pid, result.getDecisionCode(), result.getVersion());
        return ApiResponse.success("Version published", result);
    }

    @PostMapping("/versions/{pid}/submit-for-approval")
    @Operation(summary = "Submit a validated version for approval (M7 governance)",
            description = "Transitions VALIDATED → PENDING_APPROVAL for 4-eyes review")
    @RequirePermission(MetaPermission.DRT_DEFINITION_PUBLISH)
    public ApiResponse<DrtVersionDTO> submitForApproval(
            @Parameter(description = "Version PID") @PathVariable @NotBlank String pid) {
        return ApiResponse.success("Submitted for approval", versionService.submitForApproval(pid));
    }

    @PostMapping("/versions/{pid}/approve")
    @Operation(summary = "Approve + publish a pending version (M7 governance)",
            description = "Transitions PENDING_APPROVAL → PUBLISHED; records the approver")
    @RequirePermission(MetaPermission.DRT_DEFINITION_APPROVE)
    public ApiResponse<DrtVersionDTO> approveVersion(
            @Parameter(description = "Version PID") @PathVariable @NotBlank String pid,
            @RequestParam(required = false) String note,
            @RequestBody(required = false) DecisionVersionTransitionRequest request) {
        return ApiResponse.success("Version approved + published",
                versionService.approve(pid, transitionNote(request, note), impactAcknowledged(request)));
    }

    @PostMapping("/versions/{pid}/reject")
    @Operation(summary = "Reject a pending version (M7 governance)",
            description = "Transitions PENDING_APPROVAL → REJECTED; records the reason")
    @RequirePermission(MetaPermission.DRT_DEFINITION_APPROVE)
    public ApiResponse<DrtVersionDTO> rejectVersion(
            @Parameter(description = "Version PID") @PathVariable @NotBlank String pid,
            @RequestParam(required = false) String note,
            @RequestBody(required = false) DecisionVersionTransitionRequest request) {
        return ApiResponse.success("Version rejected", versionService.reject(pid, transitionNote(request, note)));
    }

    @PostMapping("/versions/{pid}/deprecate")
    @Operation(summary = "Deprecate a published version",
            description = "Transitions PUBLISHED → DEPRECATED after blast-radius acknowledgement when needed")
    @RequirePermission(MetaPermission.DRT_DEFINITION_PUBLISH)
    public ApiResponse<DrtVersionDTO> deprecateVersion(
            @Parameter(description = "Version PID") @PathVariable @NotBlank String pid,
            @RequestParam(required = false) String note,
            @RequestBody(required = false) DecisionVersionTransitionRequest request) {
        return ApiResponse.success("Version deprecated",
                versionService.deprecate(pid, transitionNote(request, note), impactAcknowledged(request)));
    }

    @PostMapping("/versions/{pid}/retire")
    @Operation(summary = "Retire a deprecated version",
            description = "Transitions DEPRECATED → RETIRED after blast-radius acknowledgement when needed")
    @RequirePermission(MetaPermission.DRT_DEFINITION_PUBLISH)
    public ApiResponse<DrtVersionDTO> retireVersion(
            @Parameter(description = "Version PID") @PathVariable @NotBlank String pid,
            @RequestParam(required = false) String note,
            @RequestBody(required = false) DecisionVersionTransitionRequest request) {
        return ApiResponse.success("Version retired",
                versionService.retire(pid, transitionNote(request, note), impactAcknowledged(request)));
    }

    @DeleteMapping("/versions/{pid}")
    @Operation(summary = "Delete a mutable draft-like version",
            description = "Hard-deletes DRAFT/VALIDATED/PENDING_APPROVAL/REJECTED versions and clears usage-index refs. "
                    + "Published/deprecated/retired versions must use deprecate/retire instead.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
    public ApiResponse<DrtVersionDTO> deleteVersion(
            @Parameter(description = "Version PID") @PathVariable @NotBlank String pid) {
        return ApiResponse.success("Version deleted", versionService.delete(pid));
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

    @GetMapping("/logs/recent")
    @Operation(summary = "List recent evaluation logs for DSL API-backed pages")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<PageResult<DrtLogDTO>> getRecentLogs(
            @Parameter(description = "Keyword search") @RequestParam(required = false) String keyword,
            @Parameter(description = "Decision code filter") @RequestParam(required = false) String decisionCode,
            @Parameter(description = "Status filter") @RequestParam(required = false) String status,
            @Parameter(description = "Caller type filter") @RequestParam(required = false) String callerType,
            @Parameter(description = "Matched result filter") @RequestParam(required = false) Boolean matched,
            @Parameter(description = "Rollout arm filter") @RequestParam(required = false) String rolloutArm,
            @Parameter(description = "Minimum duration in milliseconds") @RequestParam(required = false) Long minDurationMs,
            @Parameter(description = "Maximum duration in milliseconds") @RequestParam(required = false) Long maxDurationMs,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        log.info("Listing recent decision logs: keyword={}, decisionCode={}, status={}, callerType={}, " +
                        "matched={}, rolloutArm={}, minDurationMs={}, maxDurationMs={}, page={}, size={}",
                keyword, decisionCode, status, callerType, matched, rolloutArm,
                minDurationMs, maxDurationMs, page, size);
        return ApiResponse.success(evaluationService.findRecentLogs(
                keyword, decisionCode, status, callerType, matched, rolloutArm,
                minDurationMs, maxDurationMs, page, size));
    }

    @GetMapping("/logs/{pid}")
    @Operation(summary = "Get one evaluation log by PID for DSL API-backed detail pages")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<DrtLogDTO> getLogByPid(
            @Parameter(description = "Log PID") @PathVariable @NotBlank String pid) {
        log.info("Getting decision log: pid={}", pid);
        DrtLogDTO result = evaluationService.findLogByPid(pid);
        if (result == null) {
            return ApiResponse.error("Decision log not found: " + pid);
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/logs")
    @Operation(summary = "Query evaluation logs by trace ID")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<List<DrtLogDTO>> getLogs(
            @Parameter(description = "Trace ID") @RequestParam @NotBlank String traceId) {
        log.info("Getting decision logs: traceId={}", traceId);
        List<DrtLogDTO> result = evaluationService.findLogsByTraceId(traceId);
        return ApiResponse.success(result);
    }

    // ==================== Dashboard ====================

    @GetMapping("/dashboard/summary")
    @Operation(summary = "Get DecisionOps dashboard summary",
            description = "Aggregates definitions, EventPolicy catalogue count, today's evaluation KPIs, "
                    + "and recent failed/retrying runtime logs for the console overview.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<DecisionDashboardDTO> getDashboardSummary() {
        log.info("Getting DecisionOps dashboard summary");
        return ApiResponse.success(dashboardService.getDashboard());
    }

    private boolean impactAcknowledged(DecisionVersionTransitionRequest request) {
        return request != null && Boolean.TRUE.equals(request.getImpactAcknowledged());
    }

    private String transitionNote(DecisionVersionTransitionRequest request, String requestParamNote) {
        if (request != null && request.getNote() != null) {
            return request.getNote();
        }
        return requestParamNote;
    }

    // ==================== Data model field catalogue ====================

    @GetMapping("/model/fields")
    @Operation(summary = "List DecisionOps data-model fields",
            description = "Aggregates persisted field_refs from validated decision versions for the F6 field catalogue.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<List<DecisionModelFieldDTO>> listModelFields() {
        log.info("Listing DecisionOps model fields");
        return ApiResponse.success(modelFieldService.listFields());
    }

    @GetMapping("/facts/catalog")
    @Operation(summary = "List DecisionOps fact catalog",
            description = "Builds the Strategy Studio fact catalog from meta models, dictionary bindings, "
                    + "reference metadata, virtual model sources, and shared runtime contexts.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<DecisionFactCatalogDTO> getFactCatalog(
            @Parameter(description = "Optional model code to scope record facts")
            @RequestParam(required = false) String modelCode) {
        log.info("Listing DecisionOps fact catalog: modelCode={}", modelCode);
        return ApiResponse.success(modelFieldService.getFactCatalog(modelCode));
    }

    @GetMapping("/actions/catalog")
    @Operation(summary = "List DecisionOps action catalog",
            description = "Reports action types backed by production EventPolicy handlers for Strategy Studio.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<DecisionActionCatalogDTO> getActionCatalog() {
        log.info("Listing DecisionOps action catalog");
        return ApiResponse.success(actionCatalogService.getActionCatalog());
    }

    // ==================== Permission governance ====================

    @GetMapping("/permissions/matrix")
    @Operation(summary = "Get DecisionOps permission matrix",
            description = "Projects tenant roles against the Decision Runtime capabilities used by the console.")
    @RequirePermission(MetaPermission.DRT_DEFINITION_READ)
    public ApiResponse<DecisionPermissionMatrixDTO> getPermissionMatrix() {
        log.info("Getting DecisionOps permission matrix");
        return ApiResponse.success(permissionMatrixService.getMatrix());
    }
}
