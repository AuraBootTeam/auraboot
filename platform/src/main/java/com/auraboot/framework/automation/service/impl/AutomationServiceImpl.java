package com.auraboot.framework.automation.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.dto.AutomationCreateRequest;
import com.auraboot.framework.automation.dto.AutomationDTO;
import com.auraboot.framework.automation.dto.AutomationLogDTO;
import com.auraboot.framework.automation.dto.AutomationUpdateRequest;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationLog;
import com.auraboot.framework.automation.mapper.AutomationLogMapper;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.automation.service.AutomationFlowTriggerDeriver;
import com.auraboot.framework.automation.service.AutomationFlowTriggerDeriver.DerivedTrigger;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import com.auraboot.framework.exception.ValidationException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Automation Service Implementation
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationServiceImpl implements AutomationService {

    private final AutomationMapper automationMapper;
    private final AutomationLogMapper automationLogMapper;
    private final AutomationTriggerService automationTriggerService;
    private final com.auraboot.framework.automation.bpm.AutomationProcessRuntime automationProcessRuntime;
    private final AutomationFlowTriggerDeriver flowTriggerDeriver;
    private final DecisionUsageIndexService usageIndexService;

    // ==================== Tenant isolation guards ====================
    // ab_automation is excluded from the global TenantLineInnerInterceptor so the
    // scheduler can scan across tenants every 60s/300s. That makes user-facing
    // queries (by pid / model / search) responsible for scoping tenant explicitly,
    // otherwise any holder of automation.* permission can read/modify another
    // tenant's automation by pid (cross-tenant IDOR). These helpers fail closed.

    /** Current tenant id, or throw NOT_FOUND if no tenant context (never match-all). */
    private Long requireCurrentTenant() {
        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tenantId == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Automation not found");
        }
        return tenantId;
    }

    /** Load an automation by pid and assert it belongs to the current tenant (404 otherwise). */
    private Automation loadOwnedAutomation(String pid) {
        Automation automation = automationMapper.findByPid(pid);
        if (automation == null || !requireCurrentTenant().equals(automation.getTenantId())) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Automation not found: " + pid);
        }
        return automation;
    }

    @Transactional
    @Override
    public AutomationDTO create(AutomationCreateRequest request) {
        log.info("Creating automation: name={}, modelCode={}, triggerType={}",
                request.getName(), request.getModelCode(), request.getTriggerType());

        validateCreateRequest(request);

        // Derive trigger fields from flowConfig when the designer saves the flow.
        // The designer POSTs only {name, description, flowConfig}; triggerType/modelCode
        // live inside flowConfig.nodes[trigger].data.config. Without this derivation,
        // designer-created automations have null trigger columns and NEVER fire.
        DerivedTrigger derived = flowTriggerDeriver.derive(request.getFlowConfig());
        if (!derived.isEmpty()) {
            // Derived values are authoritative; they override any flat request fields
            // (the two should be consistent but the flowConfig is the source of truth
            // for designer automations).
            request.setTriggerType(derived.triggerType());
            request.setModelCode(derived.modelCode());
            request.setTriggerConfig(derived.triggerConfig());
        }

        String currentUserPid = MetaContext.getCurrentUserPid();
        Long tenantId = MetaContext.getCurrentTenantId();

        Automation automation = new Automation();
        automation.setPid(UniqueIdGenerator.generate());
        automation.setTenantId(tenantId);
        automation.setName(request.getName());
        automation.setDescription(request.getDescription());
        automation.setModelCode(request.getModelCode());
        automation.setTriggerType(request.getTriggerType());
        automation.setTriggerConfig(request.getTriggerConfig());
        automation.setTriggerCondition(request.getTriggerCondition());
        // Default to an empty list when absent: the visual designer saves its steps in
        // flowConfig (compiled at enable time), not in the flat actions[] column, and
        // ab_automation.actions is NOT NULL DEFAULT '[]'. Inserting null would violate it,
        // so a designer-only save (no flat actions) must persist an empty list here.
        automation.setActions(request.getActions() != null ? request.getActions() : new ArrayList<>());
        automation.setFlowConfig(request.getFlowConfig());
        automation.setEnabled(request.getEnabled() != null ? request.getEnabled() : false);
        automation.setTriggerCount(0L);
        automation.setDeletedFlag(false);
        automation.setCreatedAt(Instant.now());
        automation.setUpdatedAt(Instant.now());
        automation.setCreatedBy(currentUserPid);
        automation.setUpdatedBy(currentUserPid);

        automationMapper.insertAutomation(automation);
        usageIndexService.refreshSource("AUTOMATION", automation.getPid());
        if (Boolean.TRUE.equals(automation.getEnabled())) {
            automationProcessRuntime.deploy(automation);
        }

        log.info("Automation created: pid={}", automation.getPid());
        return toDTO(automation);
    }

    @Override
    public AutomationDTO findByPid(String pid) {
        // Use LambdaQueryWrapper instead of @Select findByPid to ensure
        // autoResultMap = true applies typeHandlers for JSONB columns
        // (flowConfig, triggerConfig, actions).
        LambdaQueryWrapper<Automation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Automation::getPid, pid)
               .eq(Automation::getDeletedFlag, false)
               .eq(Automation::getTenantId, requireCurrentTenant());
        Automation automation = automationMapper.selectOne(wrapper);
        return automation != null ? toDTO(automation) : null;
    }

    @Transactional
    @Override
    public AutomationDTO update(String pid, AutomationUpdateRequest request) {
        log.info("Updating automation: pid={}", pid);

        Automation automation = loadOwnedAutomation(pid);

        String currentUserPid = MetaContext.getCurrentUserPid();

        // Derive trigger fields from flowConfig when the designer re-saves the flow.
        // Same gap as create(): the designer only sends flowConfig, so we must populate
        // triggerType/modelCode/triggerConfig from the trigger node inside it.
        if (request.getFlowConfig() != null && !request.getFlowConfig().isEmpty()) {
            DerivedTrigger derived = flowTriggerDeriver.derive(request.getFlowConfig());
            if (!derived.isEmpty()) {
                request.setTriggerType(derived.triggerType());
                request.setTriggerConfig(derived.triggerConfig());
                // modelCode is not a field on AutomationUpdateRequest, but we apply it
                // directly to the entity here so updates also keep modelCode in sync.
                automation.setModelCode(derived.modelCode());
            }
        }

        if (StringUtils.hasText(request.getName())) {
            automation.setName(request.getName());
        }
        if (request.getDescription() != null) {
            automation.setDescription(request.getDescription());
        }
        if (request.getTriggerType() != null) {
            automation.setTriggerType(request.getTriggerType());
        }
        if (request.getTriggerConfig() != null) {
            automation.setTriggerConfig(request.getTriggerConfig());
        }
        if (request.getTriggerCondition() != null) {
            automation.setTriggerCondition(request.getTriggerCondition());
        }
        if (request.getActions() != null) {
            automation.setActions(request.getActions());
        }
        if (request.getFlowConfig() != null) {
            automation.setFlowConfig(request.getFlowConfig());
        }
        if (request.getEnabled() != null) {
            automation.setEnabled(request.getEnabled());
        }

        automation.setUpdatedAt(Instant.now());
        automation.setUpdatedBy(currentUserPid);

        automationMapper.updateAutomation(automation);
        usageIndexService.refreshSource("AUTOMATION", automation.getPid());

        log.info("Automation updated: pid={}", pid);
        return toDTO(automation);
    }

    @Transactional
    @Override
    public void delete(String pid) {
        log.info("Deleting automation: pid={}", pid);

        Automation automation = loadOwnedAutomation(pid);

        automationMapper.deleteById(automation.getId());
        usageIndexService.deleteSource("AUTOMATION", automation.getPid());

        log.info("Automation deleted: pid={}", pid);
    }

    @Override
    public List<AutomationDTO> getByModelCode(String modelCode) {
        // Tenant-scoped (ab_automation bypasses the global tenant interceptor — see guards above).
        LambdaQueryWrapper<Automation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Automation::getModelCode, modelCode)
               .eq(Automation::getDeletedFlag, false)
               .eq(Automation::getTenantId, requireCurrentTenant())
               .orderByDesc(Automation::getCreatedAt);
        return automationMapper.selectList(wrapper).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<AutomationDTO> getEnabledByModelCode(String modelCode) {
        // Tenant-scoped (ab_automation bypasses the global tenant interceptor — see guards above).
        LambdaQueryWrapper<Automation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Automation::getModelCode, modelCode)
               .eq(Automation::getEnabled, true)
               .eq(Automation::getDeletedFlag, false)
               .eq(Automation::getTenantId, requireCurrentTenant())
               .orderByAsc(Automation::getCreatedAt);
        return automationMapper.selectList(wrapper).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public PageResult<AutomationDTO> search(
            String keyword,
            String modelCode,
            String triggerType,
            Boolean enabled,
            int page,
            int size) {

        LambdaQueryWrapper<Automation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Automation::getDeletedFlag, false)
               .eq(Automation::getTenantId, requireCurrentTenant());

        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w
                    .like(Automation::getName, keyword)
                    .or()
                    .like(Automation::getDescription, keyword));
        }
        if (StringUtils.hasText(modelCode)) {
            wrapper.eq(Automation::getModelCode, modelCode);
        }
        if (StringUtils.hasText(triggerType)) {
            wrapper.eq(Automation::getTriggerType, triggerType);
        }
        if (enabled != null) {
            wrapper.eq(Automation::getEnabled, enabled);
        }

        wrapper.orderByDesc(Automation::getCreatedAt);

        Page<Automation> pageResult = automationMapper.selectPage(
                new Page<>(page, size), wrapper);

        List<AutomationDTO> dtos = pageResult.getRecords().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());

        PageResult<AutomationDTO> result = new PageResult<>();
        result.setRecords(dtos);
        result.setTotal(pageResult.getTotal());
        result.setCurrent((long) page);
        result.setSize((long) size);
        result.setPages(pageResult.getPages());
        result.setHasPrevious(page > 1);
        result.setHasNext(page < pageResult.getPages());
        return result;
    }

    @Transactional
    @Override
    public AutomationDTO enable(String pid) {
        log.info("Enabling automation: pid={}", pid);

        Automation automation = loadOwnedAutomation(pid);

        String currentUserPid = MetaContext.getCurrentUserPid();
        automationMapper.updateEnabled(pid, true, currentUserPid);

        automation.setEnabled(true);
        usageIndexService.refreshSource("AUTOMATION", automation.getPid());

        // Trigger execution always goes through SmartEngine. The compiler supports both
        // visual flowConfig and flat actions[], so both shapes must be deployed on enable.
        automationProcessRuntime.deploy(automation);

        log.info("Automation enabled: pid={}", pid);
        return toDTO(automation);
    }

    @Transactional
    @Override
    public AutomationDTO disable(String pid) {
        log.info("Disabling automation: pid={}", pid);

        Automation automation = loadOwnedAutomation(pid);

        String currentUserPid = MetaContext.getCurrentUserPid();
        automationMapper.updateEnabled(pid, false, currentUserPid);

        automation.setEnabled(false);
        usageIndexService.refreshSource("AUTOMATION", automation.getPid());
        log.info("Automation disabled: pid={}", pid);
        return toDTO(automation);
    }

    @Override
    public List<AutomationLogDTO> getLogs(String automationId, int limit) {
        List<AutomationLog> logs = automationLogMapper.findByAutomationId(automationId, limit);
        return logs.stream()
                .map(this::toLogDTO)
                .collect(Collectors.toList());
    }

    @Override
    public AutomationLogDTO getLogByPid(String logPid) {
        AutomationLog log = automationLogMapper.findByPid(logPid);
        return log != null ? toLogDTO(log) : null;
    }

    @Override
    public List<AutomationLogDTO> getRecentFailedLogs(int limit) {
        List<AutomationLog> logs = automationLogMapper.findByStatus("failed", limit);
        return logs.stream()
                .map(this::toLogDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    @Override
    public int cleanupOldLogs(int daysToKeep) {
        Instant cutoff = Instant.now().minus(daysToKeep, ChronoUnit.DAYS);
        int deleted = automationLogMapper.deleteOlderThan(cutoff);
        log.info("Cleaned up {} automation logs older than {} days", deleted, daysToKeep);
        return deleted;
    }

    @Transactional
    @Override
    public AutomationLogDTO triggerManually(String pid, String recordPid) {
        log.info("Manually triggering automation: pid={}, recordPid={}", pid, recordPid);

        Automation automation = loadOwnedAutomation(pid);

        AutomationLog logEntry = automationTriggerService.executeAutomation(
                automation,
                recordPid,
                Map.of("manualTrigger", true));

        return toLogDTO(logEntry);
    }

    // ==================== Toggle / Duplicate / Validate ====================

    @Transactional
    @Override
    public AutomationDTO toggle(String pid) {
        Automation automation = loadOwnedAutomation(pid);
        boolean newState = !automation.isActive();
        // Delegate to enable()/disable() so the SmartEngine deploy step is shared
        // and cannot drift. Previously toggle() only flipped the `enabled` flag and
        // skipped the deploy that enable() performs — a visual-flow automation
        // enabled from the list page (the "Enable" button calls /toggle, not
        // /enable) was marked Enabled but its compiled flow was never deployed, so
        // the trigger path failed at runtime with
        // "Process definition version not found for id: auto_<pid>".
        return newState ? enable(pid) : disable(pid);
    }

    @Transactional
    @Override
    public AutomationDTO duplicate(String pid) {
        Automation source = loadOwnedAutomation(pid);

        String currentUserPid = MetaContext.getCurrentUserPid();
        Long tenantId = MetaContext.getCurrentTenantId();

        Automation copy = new Automation();
        copy.setPid(UniqueIdGenerator.generate());
        copy.setTenantId(tenantId);
        copy.setName(source.getName() + " (Copy)");
        copy.setDescription(source.getDescription());
        copy.setModelCode(source.getModelCode());
        copy.setTriggerType(source.getTriggerType());
        copy.setTriggerConfig(source.getTriggerConfig());
        copy.setTriggerCondition(source.getTriggerCondition());
        copy.setActions(source.getActions() != null ? new ArrayList<>(source.getActions()) : null);
        copy.setFlowConfig(source.getFlowConfig() != null ? new HashMap<>(source.getFlowConfig()) : null);
        copy.setEnabled(false);
        copy.setTriggerCount(0L);
        copy.setDeletedFlag(false);
        copy.setCreatedAt(Instant.now());
        copy.setUpdatedAt(Instant.now());
        copy.setCreatedBy(currentUserPid);
        copy.setUpdatedBy(currentUserPid);

        automationMapper.insertAutomation(copy);
        usageIndexService.refreshSource("AUTOMATION", copy.getPid());
        log.info("Automation duplicated: source={}, newPid={}", pid, copy.getPid());
        return toDTO(copy);
    }

    @Override
    public Map<String, Object> validate(AutomationCreateRequest request) {
        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();

        if (!StringUtils.hasText(request.getName())) {
            errors.add("Automation name is required");
        }
        if (!StringUtils.hasText(request.getModelCode()) && (request.getFlowConfig() == null || request.getFlowConfig().isEmpty())) {
            errors.add("Model code is required (unless using visual designer)");
        }
        if (StringUtils.hasText(request.getTriggerType())) {
            List<String> validTriggerTypes = List.of(
                    "on_record_create", "on_record_update", "on_field_change",
                    "on_state_change", "scheduled", "webhook", "on_bpm_event");
            if (!validTriggerTypes.contains(request.getTriggerType())) {
                errors.add("Invalid trigger type: " + request.getTriggerType());
            }
        }
        if (request.getActions() != null) {
            for (int i = 0; i < request.getActions().size(); i++) {
                var action = request.getActions().get(i);
                if (!StringUtils.hasText(action.getType())) {
                    errors.add("Action #" + (i + 1) + " is missing type");
                }
            }
        }

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        return result;
    }

    // ==================== Private Helper Methods ====================

    private void validateCreateRequest(AutomationCreateRequest request) {
        if (!StringUtils.hasText(request.getName())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Automation name is required");
        }

        // Visual-designer mode (modelCode/triggerType/actions derived from flowConfig)
        // applies only when the flowConfig actually carries nodes. A degenerate
        // flowConfig like {nodes:[],edges:[]} is a non-empty Map but has no trigger
        // node, so it must still go through the flat-field required checks — otherwise
        // modelCode stays null and the ab_automation NOT NULL insert crashes with a
        // raw 500 instead of a clean 400. Mirrors the node check in enable().
        Object flowNodes = request.getFlowConfig() != null ? request.getFlowConfig().get("nodes") : null;
        boolean isFlowConfigOnly = flowNodes instanceof List<?> nodeList && !nodeList.isEmpty();

        if (!isFlowConfigOnly) {
            if (!StringUtils.hasText(request.getModelCode())) {
                throw new ValidationException(ResponseCode.CommonValidationFailed, "Model code is required");
            }
            if (!StringUtils.hasText(request.getTriggerType())) {
                throw new ValidationException(ResponseCode.CommonValidationFailed, "Trigger type is required");
            }
            if (request.getActions() == null || request.getActions().isEmpty()) {
                throw new ValidationException(ResponseCode.CommonValidationFailed, "At least one action is required");
            }
        }

        // Validate trigger type if provided
        if (StringUtils.hasText(request.getTriggerType())) {
            List<String> validTriggerTypes = List.of(
                    "on_record_create", "on_record_update", "on_field_change",
                    "on_state_change", "scheduled", "webhook", "on_bpm_event");
            if (!validTriggerTypes.contains(request.getTriggerType())) {
                throw new ValidationException(ResponseCode.CommonValidationFailed,
                        "Invalid trigger type: " + request.getTriggerType());
            }
        }
    }

    private AutomationDTO toDTO(Automation entity) {
        if (entity == null) {
            return null;
        }

        return AutomationDTO.builder()
                .id(entity.getId())
                .pid(entity.getPid())
                .tenantId(entity.getTenantId())
                .name(entity.getName())
                .description(entity.getDescription())
                .modelCode(entity.getModelCode())
                .triggerType(entity.getTriggerType())
                .triggerConfig(entity.getTriggerConfig())
                .triggerCondition(entity.getTriggerCondition())
                .actions(entity.getActions())
                .flowConfig(entity.getFlowConfig())
                .enabled(entity.getEnabled())
                .lastTriggeredAt(entity.getLastTriggeredAt())
                .triggerCount(entity.getTriggerCount())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    private AutomationLogDTO toLogDTO(AutomationLog entity) {
        if (entity == null) {
            return null;
        }

        return AutomationLogDTO.builder()
                .id(entity.getId())
                .pid(entity.getPid())
                .tenantId(entity.getTenantId())
                .automationId(entity.getAutomationId())
                .triggerType(entity.getTriggerType())
                .triggerRecordPid(entity.getTriggerRecordPid())
                .triggerPayload(entity.getTriggerPayload())
                .status(entity.getStatus())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .durationMs(entity.getDurationMs())
                .errorMessage(entity.getErrorMessage())
                .actionResults(entity.getActionResults())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
