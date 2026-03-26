package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.DecisionDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.EvidenceSubmitRequest;
import com.auraboot.framework.meta.entity.DecisionAlarm;
import com.auraboot.framework.meta.entity.DecisionDefinition;
import com.auraboot.framework.meta.entity.DecisionRecord;
import com.auraboot.framework.meta.entity.EvidenceRecord;
import com.auraboot.framework.meta.mapper.DecisionAlarmMapper;
import com.auraboot.framework.meta.mapper.DecisionRecordMapper;
import com.auraboot.framework.meta.service.AdjudicatorService;
import com.auraboot.framework.meta.service.DecisionDefinitionService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Decision Controller.
 * APIs for decision definitions, evidence submission, adjudication, and alarms.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/decisions")
@RequiredArgsConstructor
public class DecisionController {

    private final DecisionDefinitionService definitionService;
    private final AdjudicatorService adjudicatorService;
    private final DecisionRecordMapper decisionRecordMapper;
    private final DecisionAlarmMapper alarmMapper;

    // ==================== Definition CRUD ====================

    @PostMapping("/definitions")
    @RequirePermission(MetaPermission.DECISION_MANAGE)
    public ApiResponse<DecisionDefinition> createDefinition(@Valid @RequestBody DecisionDefinitionCreateRequest request) {
        DecisionDefinition definition = definitionService.create(request);
        return ApiResponse.success(definition);
    }

    @GetMapping("/definitions/{pid}")
    @RequirePermission(MetaPermission.DECISION_READ)
    public ApiResponse<DecisionDefinition> getDefinition(@PathVariable String pid) {
        DecisionDefinition definition = definitionService.getByPid(pid);
        return ApiResponse.success(definition);
    }

    @GetMapping("/definitions/code/{code}")
    @RequirePermission(MetaPermission.DECISION_READ)
    public ApiResponse<DecisionDefinition> getDefinitionByCode(@PathVariable String code) {
        DecisionDefinition definition = definitionService.getCurrentByCode(code);
        return ApiResponse.success(definition);
    }

    @GetMapping("/definitions/subject/{subjectType}")
    @RequirePermission(MetaPermission.DECISION_READ)
    public ApiResponse<List<DecisionDefinition>> listDefinitions(@PathVariable String subjectType) {
        List<DecisionDefinition> definitions = definitionService.listBySubjectType(subjectType);
        return ApiResponse.success(definitions);
    }

    @PutMapping("/definitions/{pid}")
    @RequirePermission(MetaPermission.DECISION_MANAGE)
    public ApiResponse<DecisionDefinition> updateDefinition(@PathVariable String pid,
                                                             @Valid @RequestBody DecisionDefinitionCreateRequest request) {
        DecisionDefinition definition = definitionService.update(pid, request);
        return ApiResponse.success(definition);
    }

    @PostMapping("/definitions/{pid}/publish")
    @RequirePermission(MetaPermission.DECISION_MANAGE)
    public ApiResponse<Void> publishDefinition(@PathVariable String pid) {
        definitionService.publish(pid);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/definitions/{pid}")
    @RequirePermission(MetaPermission.DECISION_MANAGE)
    public ApiResponse<Void> deleteDefinition(@PathVariable String pid) {
        definitionService.delete(pid);
        return ApiResponse.success(null);
    }

    // ==================== Evidence ====================

    @PostMapping("/evidence")
    @RequirePermission(MetaPermission.DECISION_EXECUTE)
    public ApiResponse<EvidenceRecord> submitEvidence(@Valid @RequestBody EvidenceSubmitRequest request) {
        EvidenceRecord record = adjudicatorService.submitEvidence(request);
        return ApiResponse.success(record);
    }

    @GetMapping("/evidence/{subjectType}/{subjectId}/{stage}")
    @RequirePermission(MetaPermission.DECISION_READ)
    public ApiResponse<List<EvidenceRecord>> getEvidence(@PathVariable String subjectType,
                                                          @PathVariable String subjectId,
                                                          @PathVariable String stage) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<EvidenceRecord> evidence = adjudicatorService.getEvidence(tenantId, subjectType, subjectId, stage);
        return ApiResponse.success(evidence);
    }

    // ==================== Adjudication ====================

    @PostMapping("/adjudicate")
    @RequirePermission(MetaPermission.DECISION_EXECUTE)
    public ApiResponse<DecisionRecord> adjudicate(@RequestBody Map<String, String> request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String subjectType = request.get("subjectType");
        String subjectId = request.get("subjectId");
        String stage = request.get("stage");
        String outcome = request.get("outcome");

        DecisionRecord decision = adjudicatorService.adjudicate(tenantId, subjectType, subjectId,
                stage, outcome, userId);
        return ApiResponse.success(decision);
    }

    @GetMapping("/{subjectType}/{subjectId}")
    @RequirePermission(MetaPermission.DECISION_READ)
    public ApiResponse<List<DecisionRecord>> getDecisions(@PathVariable String subjectType,
                                                           @PathVariable String subjectId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<DecisionRecord> decisions = decisionRecordMapper.findBySubjectId(tenantId, subjectType, subjectId);
        return ApiResponse.success(decisions);
    }

    @GetMapping("/{subjectType}/{subjectId}/{stage}")
    @RequirePermission(MetaPermission.DECISION_READ)
    public ApiResponse<DecisionRecord> getDecision(@PathVariable String subjectType,
                                                    @PathVariable String subjectId,
                                                    @PathVariable String stage) {
        Long tenantId = MetaContext.getCurrentTenantId();
        DecisionRecord decision = adjudicatorService.getDecision(tenantId, subjectType, subjectId, stage);
        return ApiResponse.success(decision);
    }

    // ==================== Alarms ====================

    @GetMapping("/alarms")
    @RequirePermission(MetaPermission.DECISION_READ)
    public ApiResponse<List<DecisionAlarm>> getAlarms(@RequestParam(defaultValue = "50") int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<DecisionAlarm> alarms = alarmMapper.findOpenAlarms(tenantId, limit);
        return ApiResponse.success(alarms);
    }

    @PostMapping("/alarms/{id}/acknowledge")
    @RequirePermission(MetaPermission.DECISION_MANAGE)
    public ApiResponse<Void> acknowledgeAlarm(@PathVariable Long id) {
        alarmMapper.acknowledgeAlarm(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/alarms/{id}/resolve")
    @RequirePermission(MetaPermission.DECISION_MANAGE)
    public ApiResponse<Void> resolveAlarm(@PathVariable Long id) {
        alarmMapper.resolveAlarm(id);
        return ApiResponse.success(null);
    }
}
