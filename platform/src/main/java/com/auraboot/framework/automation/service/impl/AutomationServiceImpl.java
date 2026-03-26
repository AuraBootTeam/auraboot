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
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.UniqueIdGenerator;
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

    @Transactional
    @Override
    public AutomationDTO create(AutomationCreateRequest request) {
        log.info("Creating automation: name={}, modelCode={}, triggerType={}",
                request.getName(), request.getModelCode(), request.getTriggerType());

        validateCreateRequest(request);

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
        automation.setActions(request.getActions());
        automation.setFlowConfig(request.getFlowConfig());
        automation.setEnabled(request.getEnabled() != null ? request.getEnabled() : false);
        automation.setTriggerCount(0L);
        automation.setDeletedFlag(false);
        automation.setCreatedAt(Instant.now());
        automation.setUpdatedAt(Instant.now());
        automation.setCreatedBy(currentUserPid);
        automation.setUpdatedBy(currentUserPid);

        automationMapper.insertAutomation(automation);

        log.info("Automation created: pid={}", automation.getPid());
        return toDTO(automation);
    }

    @Override
    public AutomationDTO findByPid(String pid) {
        Automation automation = automationMapper.findByPid(pid);
        return automation != null ? toDTO(automation) : null;
    }

    @Transactional
    @Override
    public AutomationDTO update(String pid, AutomationUpdateRequest request) {
        log.info("Updating automation: pid={}", pid);

        Automation automation = automationMapper.findByPid(pid);
        if (automation == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Automation not found: " + pid);
        }

        String currentUserPid = MetaContext.getCurrentUserPid();

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

        log.info("Automation updated: pid={}", pid);
        return toDTO(automation);
    }

    @Transactional
    @Override
    public void delete(String pid) {
        log.info("Deleting automation: pid={}", pid);

        Automation automation = automationMapper.findByPid(pid);
        if (automation == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Automation not found: " + pid);
        }

        automationMapper.deleteById(automation.getId());

        log.info("Automation deleted: pid={}", pid);
    }

    @Override
    public List<AutomationDTO> getByModelCode(String modelCode) {
        List<Automation> automations = automationMapper.findByModelCode(modelCode);
        return automations.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<AutomationDTO> getEnabledByModelCode(String modelCode) {
        List<Automation> automations = automationMapper.findEnabledByModelCode(modelCode);
        return automations.stream()
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
        wrapper.eq(Automation::getDeletedFlag, false);

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

        Automation automation = automationMapper.findByPid(pid);
        if (automation == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Automation not found: " + pid);
        }

        String currentUserPid = MetaContext.getCurrentUserPid();
        automationMapper.updateEnabled(pid, true, currentUserPid);

        automation.setEnabled(true);
        log.info("Automation enabled: pid={}", pid);
        return toDTO(automation);
    }

    @Transactional
    @Override
    public AutomationDTO disable(String pid) {
        log.info("Disabling automation: pid={}", pid);

        Automation automation = automationMapper.findByPid(pid);
        if (automation == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Automation not found: " + pid);
        }

        String currentUserPid = MetaContext.getCurrentUserPid();
        automationMapper.updateEnabled(pid, false, currentUserPid);

        automation.setEnabled(false);
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
    public AutomationLogDTO triggerManually(String pid, String recordId) {
        log.info("Manually triggering automation: pid={}, recordId={}", pid, recordId);

        Automation automation = automationMapper.findByPid(pid);
        if (automation == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Automation not found: " + pid);
        }

        AutomationLog logEntry = automationTriggerService.executeAutomation(
                automation,
                recordId,
                Map.of("manualTrigger", true));

        return toLogDTO(logEntry);
    }

    // ==================== Toggle / Duplicate / Validate ====================

    @Transactional
    @Override
    public AutomationDTO toggle(String pid) {
        Automation automation = automationMapper.findByPid(pid);
        if (automation == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Automation not found: " + pid);
        }
        boolean newState = !automation.isActive();
        String currentUserPid = MetaContext.getCurrentUserPid();
        automationMapper.updateEnabled(pid, newState, currentUserPid);
        automation.setEnabled(newState);
        log.info("Automation toggled: pid={}, enabled={}", pid, newState);
        return toDTO(automation);
    }

    @Transactional
    @Override
    public AutomationDTO duplicate(String pid) {
        Automation source = automationMapper.findByPid(pid);
        if (source == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Automation not found: " + pid);
        }

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
                    "on_state_change", "scheduled", "webhook");
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

        // If flowConfig is provided (visual designer mode), modelCode/triggerType/actions are optional
        boolean isFlowConfigOnly = request.getFlowConfig() != null && !request.getFlowConfig().isEmpty();

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
                    "on_state_change", "scheduled", "webhook");
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
                .triggerRecordId(entity.getTriggerRecordId())
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
