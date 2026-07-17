package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.mapper.SlaConfigMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import com.auraboot.framework.plugin.dto.imports.SlaConfigDefinitionDTO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlaConfigService {

    private final SlaConfigMapper slaConfigMapper;
    private final DecisionUsageIndexService usageIndexService;

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

    public List<SlaConfigEntity> findByTargetAnyCase(String targetType, String targetKey) {
        if (!StringUtils.hasText(targetType)) {
            return List.of();
        }
        Set<String> variants = new LinkedHashSet<>();
        variants.add(targetType);
        variants.add(targetType.toUpperCase(Locale.ROOT));
        variants.add(targetType.toLowerCase(Locale.ROOT));
        return slaConfigMapper.findByTargetTypes(MetaContext.getCurrentTenantId(), variants, targetKey);
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
                .ruleBinding(request.ruleBinding())
                .actionPolicy(request.actionPolicy())
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
        usageIndexService.refreshSource("SLA_RULE", entity.getPid());
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
        if (request.ruleBinding() != null) entity.setRuleBinding(request.ruleBinding());
        if (request.actionPolicy() != null) entity.setActionPolicy(request.actionPolicy());
        if (request.modelCode() != null) entity.setModelCode(request.modelCode());
        if (request.deadlineField() != null) entity.setDeadlineField(request.deadlineField());
        if (request.priorityField() != null) entity.setPriorityField(request.priorityField());
        if (request.suspendPolicy() != null) entity.setSuspendPolicy(request.suspendPolicy());
        if (request.enabled() != null) entity.setEnabled(request.enabled());
        entity.setUpdatedAt(Instant.now());
        slaConfigMapper.updateById(entity);
        usageIndexService.refreshSource("SLA_RULE", entity.getPid());
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
        usageIndexService.deleteSource("SLA_RULE", entity.getPid());
        log.info("Deleted SLA config: pid={}", pid);
    }

    public record CreateSlaConfigRequest(
            String name, String targetType, String targetKey, String domainCode,
            String deadlineMode, String deadlineValue, Boolean businessCalendar,
            List<Map<String, Object>> warningRules,
            RuleConsumerBinding ruleBinding,
            Map<String, Object> actionPolicy,
            String modelCode, String deadlineField, String priorityField,
            String suspendPolicy) {
        public CreateSlaConfigRequest(
                String name, String targetType, String targetKey, String domainCode,
                String deadlineMode, String deadlineValue, Boolean businessCalendar,
                List<Map<String, Object>> warningRules,
                RuleConsumerBinding ruleBinding,
                String modelCode, String deadlineField, String priorityField,
                String suspendPolicy) {
            this(name, targetType, targetKey, domainCode, deadlineMode, deadlineValue,
                    businessCalendar, warningRules, ruleBinding, null, modelCode, deadlineField,
                    priorityField, suspendPolicy);
        }
    }

    /**
     * Upsert an SLA config from a plugin import DTO.
     *
     * <p>Display names are localized, so import identity must not be based on
     * {@code name}. Prefer {@code slaKey} / {@code ruleBinding.consumerCode};
     * fall back to the target tuple for older seeds that do not carry a stable
     * key. If earlier imports created duplicate rows, keep the oldest row and
     * soft-delete the later duplicates.
     */
    @Transactional
    public SlaConfigEntity importSlaConfig(SlaConfigDefinitionDTO dto) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<SlaConfigEntity> matches = findImportMatches(tenantId, dto);
        SlaConfigEntity existing = matches.isEmpty() ? null : matches.get(0);

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
                    .ruleBinding(dto.getRuleBinding())
                    .actionPolicy(dto.getActionPolicy())
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
            usageIndexService.refreshSource("SLA_RULE", entity.getPid());
            log.info("Imported SLA config (created): name={}, pid={}", entity.getName(), entity.getPid());
            return entity;
        }

        for (int i = 1; i < matches.size(); i++) {
            SlaConfigEntity duplicate = matches.get(i);
            if (duplicate.getId() != null) {
                slaConfigMapper.deleteById(duplicate.getId());
                usageIndexService.deleteSource("SLA_RULE", duplicate.getPid());
                log.warn("Removed duplicate imported SLA config: keptPid={}, duplicatePid={}, name={}",
                        existing.getPid(), duplicate.getPid(), duplicate.getName());
            }
        }

        existing.setName(dto.getName());
        existing.setTargetType(dto.getTargetType());
        existing.setTargetKey(dto.getTargetKey());
        existing.setDomainCode(dto.getDomainCode());
        existing.setDeadlineMode(dto.getDeadlineMode());
        existing.setDeadlineValue(dto.getDeadlineValue());
        if (dto.getBusinessCalendar() != null) existing.setBusinessCalendar(dto.getBusinessCalendar());
        existing.setWarningRules(dto.getWarningRules());
        existing.setRuleBinding(dto.getRuleBinding());
        existing.setActionPolicy(dto.getActionPolicy());
        existing.setModelCode(dto.getModelCode());
        existing.setDeadlineField(dto.getDeadlineField());
        existing.setPriorityField(dto.getPriorityField());
        if (dto.getSuspendPolicy() != null) existing.setSuspendPolicy(dto.getSuspendPolicy());
        if (dto.getEnabled() != null) existing.setEnabled(dto.getEnabled());
        existing.setUpdatedAt(now);
        slaConfigMapper.updateById(existing);
        usageIndexService.refreshSource("SLA_RULE", existing.getPid());
        log.info("Imported SLA config (updated): name={}, pid={}", existing.getName(), existing.getPid());
        return existing;
    }

    private List<SlaConfigEntity> findImportMatches(Long tenantId, SlaConfigDefinitionDTO dto) {
        String stableKey = importStableKey(dto);
        if (StringUtils.hasText(stableKey)) {
            List<SlaConfigEntity> byConsumerCode = slaConfigMapper.selectList(new QueryWrapper<SlaConfigEntity>()
                    .eq("tenant_id", tenantId)
                    .eq("deleted_flag", false)
                    .apply("rule_binding ->> 'consumerCode' = {0}", stableKey)
                    .orderByAsc("id"));
            if (byConsumerCode != null && !byConsumerCode.isEmpty()) {
                return byConsumerCode;
            }
        }

        if (StringUtils.hasText(dto.getTargetType()) && StringUtils.hasText(dto.getTargetKey())) {
            QueryWrapper<SlaConfigEntity> targetWrapper = new QueryWrapper<SlaConfigEntity>()
                    .eq("tenant_id", tenantId)
                    .eq("deleted_flag", false)
                    .eq("target_type", dto.getTargetType())
                    .eq("target_key", dto.getTargetKey())
                    .orderByAsc("id");
            if (StringUtils.hasText(dto.getDomainCode())) {
                targetWrapper.eq("domain_code", dto.getDomainCode());
            }
            List<SlaConfigEntity> byTarget = slaConfigMapper.selectList(targetWrapper);
            if (byTarget != null && !byTarget.isEmpty()) {
                return byTarget;
            }
        }

        if (!StringUtils.hasText(dto.getName())) {
            return List.of();
        }
        List<SlaConfigEntity> byName = slaConfigMapper.selectList(new QueryWrapper<SlaConfigEntity>()
                .eq("tenant_id", tenantId)
                .eq("deleted_flag", false)
                .eq("name", dto.getName())
                .orderByAsc("id"));
        return byName == null ? List.of() : byName;
    }

    private String importStableKey(SlaConfigDefinitionDTO dto) {
        if (StringUtils.hasText(dto.getSlaKey())) {
            return dto.getSlaKey();
        }
        RuleConsumerBinding binding = dto.getRuleBinding();
        if (binding != null && StringUtils.hasText(binding.consumerCode())) {
            return binding.consumerCode();
        }
        return null;
    }

    public record UpdateSlaConfigRequest(
            String name, String targetType, String targetKey, String domainCode,
            String deadlineMode, String deadlineValue, Boolean businessCalendar,
            List<Map<String, Object>> warningRules,
            RuleConsumerBinding ruleBinding,
            Map<String, Object> actionPolicy,
            String modelCode, String deadlineField, String priorityField,
            String suspendPolicy, Boolean enabled) {
        public UpdateSlaConfigRequest(
                String name, String targetType, String targetKey, String domainCode,
                String deadlineMode, String deadlineValue, Boolean businessCalendar,
                List<Map<String, Object>> warningRules,
                RuleConsumerBinding ruleBinding,
                String modelCode, String deadlineField, String priorityField,
                String suspendPolicy, Boolean enabled) {
            this(name, targetType, targetKey, domainCode, deadlineMode, deadlineValue,
                    businessCalendar, warningRules, ruleBinding, null, modelCode, deadlineField,
                    priorityField, suspendPolicy, enabled);
        }
    }
}
