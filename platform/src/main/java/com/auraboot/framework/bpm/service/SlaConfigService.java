package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.mapper.SlaConfigMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.plugin.dto.imports.SlaConfigDefinitionDTO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

    /**
     * Upsert an SLA config from a plugin import DTO. Uses {@code (tenantId, name)}
     * as the unique key (the entity has no dedicated code column). Existing rows
     * are updated in place (preserving pid); missing rows are inserted.
     */
    @Transactional
    public SlaConfigEntity importSlaConfig(SlaConfigDefinitionDTO dto) {
        Long tenantId = MetaContext.getCurrentTenantId();
        SlaConfigEntity existing = slaConfigMapper.selectOne(new QueryWrapper<SlaConfigEntity>()
                .eq("tenant_id", tenantId)
                .eq("name", dto.getName()));

        Instant now = Instant.now();
        if (existing == null) {
            SlaConfigEntity entity = SlaConfigEntity.builder()
                    .pid(UlidGenerator.generate())
                    .tenantId(tenantId)
                    .name(dto.getName())
                    .targetType(dto.getTargetType())
                    .targetKey(dto.getTargetKey())
                    .domainCode(dto.getDomainCode())
                    .deadlineMode(dto.getDeadlineMode())
                    .deadlineValue(dto.getDeadlineValue())
                    .businessCalendar(dto.getBusinessCalendar() != null ? dto.getBusinessCalendar() : Boolean.FALSE)
                    .warningRules(dto.getWarningRules())
                    .modelCode(dto.getModelCode())
                    .deadlineField(dto.getDeadlineField())
                    .priorityField(dto.getPriorityField())
                    .suspendPolicy(dto.getSuspendPolicy() != null ? dto.getSuspendPolicy() : "pause")
                    .enabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled())
                    .createdAt(now)
                    .updatedAt(now)
                    .deletedFlag(false)
                    .build();
            slaConfigMapper.insert(entity);
            log.info("Imported SLA config (created): name={}, pid={}", entity.getName(), entity.getPid());
            return entity;
        }

        existing.setTargetType(dto.getTargetType());
        existing.setTargetKey(dto.getTargetKey());
        existing.setDomainCode(dto.getDomainCode());
        existing.setDeadlineMode(dto.getDeadlineMode());
        existing.setDeadlineValue(dto.getDeadlineValue());
        if (dto.getBusinessCalendar() != null) existing.setBusinessCalendar(dto.getBusinessCalendar());
        existing.setWarningRules(dto.getWarningRules());
        existing.setModelCode(dto.getModelCode());
        existing.setDeadlineField(dto.getDeadlineField());
        existing.setPriorityField(dto.getPriorityField());
        if (dto.getSuspendPolicy() != null) existing.setSuspendPolicy(dto.getSuspendPolicy());
        if (dto.getEnabled() != null) existing.setEnabled(dto.getEnabled());
        existing.setUpdatedAt(now);
        slaConfigMapper.updateById(existing);
        log.info("Imported SLA config (updated): name={}, pid={}", existing.getName(), existing.getPid());
        return existing;
    }

    public record UpdateSlaConfigRequest(
            String name, String targetType, String targetKey, String domainCode,
            String deadlineMode, String deadlineValue, Boolean businessCalendar,
            List<Map<String, Object>> warningRules,
            String modelCode, String deadlineField, String priorityField,
            String suspendPolicy, Boolean enabled) {}
}
