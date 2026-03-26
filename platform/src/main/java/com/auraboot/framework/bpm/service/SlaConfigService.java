package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.mapper.SlaConfigMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlaConfigService {

    private final SlaConfigMapper slaConfigMapper;

    public List<SlaConfigEntity> list() {
        return slaConfigMapper.findAllEnabled(MetaContext.getCurrentTenantId());
    }

    public List<SlaConfigEntity> listAll() {
        return slaConfigMapper.findAll(MetaContext.getCurrentTenantId());
    }

    public SlaConfigEntity getByPid(String pid) {
        SlaConfigEntity entity = slaConfigMapper.findByPid(pid);
        if (entity != null && !entity.getTenantId().equals(MetaContext.getCurrentTenantId())) {
            return null;
        }
        return entity;
    }

    public List<SlaConfigEntity> findByTarget(String targetType, String targetKey) {
        return slaConfigMapper.findByTarget(MetaContext.getCurrentTenantId(), targetType, targetKey);
    }

    public List<SlaConfigEntity> findByDomain(String domainCode) {
        return slaConfigMapper.findByDomain(MetaContext.getCurrentTenantId(), domainCode);
    }

    @Transactional
    public SlaConfigEntity create(CreateSlaConfigRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        SlaConfigEntity entity = SlaConfigEntity.builder()
                .pid(UlidGenerator.generate())
                .tenantId(tenantId)
                .name(request.name())
                .targetType(request.targetType())
                .targetKey(request.targetKey())
                .domainCode(request.domainCode())
                .deadlineMode(request.deadlineMode())
                .deadlineValue(request.deadlineValue())
                .businessCalendar(request.businessCalendar() != null ? request.businessCalendar() : false)
                .warningRules(request.warningRules())
                .modelCode(request.modelCode())
                .deadlineField(request.deadlineField())
                .priorityField(request.priorityField())
                .suspendPolicy(request.suspendPolicy() != null ? request.suspendPolicy() : "pause")
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .deletedFlag(false)
                .build();
        slaConfigMapper.insert(entity);
        log.info("Created SLA config: pid={}, name={}", entity.getPid(), entity.getName());
        return entity;
    }

    @Transactional
    public SlaConfigEntity update(String pid, UpdateSlaConfigRequest request) {
        SlaConfigEntity entity = getByPid(pid);
        if (entity == null) {
            throw new IllegalArgumentException("SLA config not found: " + pid);
        }
        if (request.name() != null) entity.setName(request.name());
        if (request.targetType() != null) entity.setTargetType(request.targetType());
        if (request.targetKey() != null) entity.setTargetKey(request.targetKey());
        if (request.domainCode() != null) entity.setDomainCode(request.domainCode());
        if (request.deadlineMode() != null) entity.setDeadlineMode(request.deadlineMode());
        if (request.deadlineValue() != null) entity.setDeadlineValue(request.deadlineValue());
        if (request.businessCalendar() != null) entity.setBusinessCalendar(request.businessCalendar());
        if (request.warningRules() != null) entity.setWarningRules(request.warningRules());
        if (request.modelCode() != null) entity.setModelCode(request.modelCode());
        if (request.deadlineField() != null) entity.setDeadlineField(request.deadlineField());
        if (request.priorityField() != null) entity.setPriorityField(request.priorityField());
        if (request.suspendPolicy() != null) entity.setSuspendPolicy(request.suspendPolicy());
        if (request.enabled() != null) entity.setEnabled(request.enabled());
        entity.setUpdatedAt(Instant.now());
        slaConfigMapper.updateById(entity);
        log.info("Updated SLA config: pid={}", pid);
        return entity;
    }

    @Transactional
    public void delete(String pid) {
        SlaConfigEntity entity = getByPid(pid);
        if (entity == null) {
            throw new IllegalArgumentException("SLA config not found: " + pid);
        }
        slaConfigMapper.deleteById(entity.getId());
        log.info("Deleted SLA config: pid={}", pid);
    }

    public record CreateSlaConfigRequest(
            String name, String targetType, String targetKey, String domainCode,
            String deadlineMode, String deadlineValue, Boolean businessCalendar,
            List<Map<String, Object>> warningRules,
            String modelCode, String deadlineField, String priorityField,
            String suspendPolicy) {}

    public record UpdateSlaConfigRequest(
            String name, String targetType, String targetKey, String domainCode,
            String deadlineMode, String deadlineValue, Boolean businessCalendar,
            List<Map<String, Object>> warningRules,
            String modelCode, String deadlineField, String priorityField,
            String suspendPolicy, Boolean enabled) {}
}
