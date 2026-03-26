package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.DecisionDefinitionCreateRequest;
import com.auraboot.framework.meta.entity.DecisionDefinition;
import com.auraboot.framework.meta.mapper.DecisionDefinitionMapper;
import com.auraboot.framework.meta.service.DecisionDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

/**
 * Decision Definition Service implementation.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionDefinitionServiceImpl implements DecisionDefinitionService {

    private final DecisionDefinitionMapper definitionMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public DecisionDefinition create(DecisionDefinitionCreateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();

        DecisionDefinition definition = new DecisionDefinition();
        definition.setPid(UniqueIdGenerator.generate());
        definition.setTenantId(tenantId);
        definition.setCode(request.getCode());
        definition.setDisplayName(request.getDisplayName());
        definition.setDescription(request.getDescription());
        definition.setSubjectType(request.getSubjectType());
        definition.setStage(request.getStage());
        definition.setRequiredEvidence(serializeJson(request.getRequiredEvidence()));
        definition.setInvariants(serializeJson(request.getInvariants()));
        definition.setOutcomeOptions(serializeJson(request.getOutcomeOptions()));
        definition.setAutoAdjudicate(request.isAutoAdjudicate());
        definition.setExtension(new com.auraboot.framework.meta.entity.payload.ExtensionBean());
        definition.setVersion(1);
        definition.setIsCurrent(true);
        definition.setRowVersion(1);
        definition.setStatus(Status.DRAFT.getCode());
        definition.setDeletedFlag(false);
        definition.setCreatedAt(Instant.now());
        definition.setUpdatedAt(Instant.now());

        definitionMapper.insertIdempotent(definition);
        log.info("Created decision definition: code={}, subjectType={}, stage={}",
                request.getCode(), request.getSubjectType(), request.getStage());
        return definition;
    }

    @Override
    public DecisionDefinition getByPid(String pid) {
        DecisionDefinition definition = definitionMapper.findByPid(pid);
        if (definition == null) {
            throw new BusinessException(ResponseCode.BadParam, "Decision definition not found: " + pid);
        }
        return definition;
    }

    @Override
    public DecisionDefinition getCurrentByCode(String code) {
        Long tenantId = MetaContext.getCurrentTenantId();
        DecisionDefinition definition = definitionMapper.findCurrentByCode(tenantId, code);
        if (definition == null) {
            throw new BusinessException(ResponseCode.BadParam, "Decision definition not found: " + code);
        }
        return definition;
    }

    @Override
    public List<DecisionDefinition> listBySubjectType(String subjectType) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return definitionMapper.findBySubjectType(tenantId, subjectType);
    }

    @Override
    @Transactional
    public DecisionDefinition update(String pid, DecisionDefinitionCreateRequest request) {
        DecisionDefinition existing = getByPid(pid);

        if (!Status.DRAFT.getCode().equals(existing.getStatus())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Only DRAFT definitions can be updated. Current status: " + existing.getStatus());
        }

        existing.setDisplayName(request.getDisplayName());
        existing.setDescription(request.getDescription());
        existing.setSubjectType(request.getSubjectType());
        existing.setStage(request.getStage());
        existing.setRequiredEvidence(serializeJson(request.getRequiredEvidence()));
        existing.setInvariants(serializeJson(request.getInvariants()));
        existing.setOutcomeOptions(serializeJson(request.getOutcomeOptions()));
        existing.setAutoAdjudicate(request.isAutoAdjudicate());
        existing.setUpdatedAt(Instant.now());
        existing.setRowVersion(existing.getRowVersion() + 1);

        definitionMapper.updateById(existing);
        log.info("Updated decision definition: pid={}", pid);
        return existing;
    }

    @Override
    @Transactional
    public void publish(String pid) {
        DecisionDefinition definition = getByPid(pid);

        if (!Status.DRAFT.getCode().equals(definition.getStatus())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Only DRAFT definitions can be published. Current status: " + definition.getStatus());
        }

        definitionMapper.markAsNotCurrent(definition.getTenantId(), definition.getCode());
        definitionMapper.publishById(definition.getId(), Status.PUBLISHED.getCode());
        log.info("Published decision definition: code={}, version={}", definition.getCode(), definition.getVersion());
    }

    @Override
    @Transactional
    public void delete(String pid) {
        DecisionDefinition definition = getByPid(pid);
        definitionMapper.softDelete(pid);
        log.info("Deleted decision definition: pid={}, code={}", pid, definition.getCode());
    }

    private String serializeJson(Object value) {
        if (value == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ResponseCode.BadParam, "Failed to serialize: " + e.getMessage());
        }
    }
}
