package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.InvariantDefinitionCreateRequest;
import com.auraboot.framework.meta.entity.InvariantDefinition;
import com.auraboot.framework.meta.mapper.InvariantDefinitionMapper;
import com.auraboot.framework.meta.service.InvariantDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Invariant Definition Service implementation.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvariantDefinitionServiceImpl implements InvariantDefinitionService {

    private static final Set<String> VALID_TYPES = Set.of("pre", "post", "always");
    private static final Set<String> VALID_SEVERITIES = Set.of("error", "warn");
    private static final Set<String> VALID_SCOPES = Set.of("model", "command", "state");

    private final InvariantDefinitionMapper invariantMapper;

    @Override
    @Transactional
    public InvariantDefinition create(InvariantDefinitionCreateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        validateRequest(request);

        InvariantDefinition definition = new InvariantDefinition();
        definition.setPid(UniqueIdGenerator.generate());
        definition.setTenantId(tenantId);
        definition.setCode(request.getCode());
        definition.setDisplayName(request.getDisplayName());
        definition.setDescription(request.getDescription());
        definition.setExpression(request.getExpression());
        definition.setInvariantType(request.getInvariantType());
        definition.setSeverity(request.getSeverity());
        definition.setScopeType(request.getScopeType());
        definition.setScopeRef(request.getScopeRef());
        definition.setModelCode(request.getModelCode());
        definition.setEnabled(request.isEnabled());
        definition.setVersion(1);
        definition.setIsCurrent(true);
        definition.setRowVersion(1);
        definition.setStatus(Status.DRAFT.getCode());
        definition.setExtension(new com.auraboot.framework.meta.entity.payload.ExtensionBean());
        definition.setDeletedFlag(false);
        definition.setCreatedAt(Instant.now());
        definition.setUpdatedAt(Instant.now());

        invariantMapper.insertIdempotent(definition);
        log.info("Created invariant definition: code={}, type={}, scope={}/{}",
                request.getCode(), request.getInvariantType(), request.getScopeType(), request.getScopeRef());
        return definition;
    }

    @Override
    public InvariantDefinition getByPid(String pid) {
        InvariantDefinition definition = invariantMapper.findByPid(pid);
        if (definition == null) {
            throw new BusinessException(ResponseCode.BadParam, "Invariant not found: " + pid);
        }
        return definition;
    }

    @Override
    public InvariantDefinition getCurrentByCode(String code) {
        Long tenantId = MetaContext.getCurrentTenantId();
        InvariantDefinition definition = invariantMapper.findCurrentByCode(tenantId, code);
        if (definition == null) {
            throw new BusinessException(ResponseCode.BadParam, "Invariant not found: " + code);
        }
        return definition;
    }

    @Override
    public List<InvariantDefinition> listByModelCode(String modelCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return invariantMapper.findByModelCode(tenantId, modelCode);
    }

    @Override
    @Transactional
    public InvariantDefinition update(String pid, InvariantDefinitionCreateRequest request) {
        InvariantDefinition existing = getByPid(pid);

        if (!Status.DRAFT.getCode().equals(existing.getStatus())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Only DRAFT invariants can be updated. Current status: " + existing.getStatus());
        }

        validateRequest(request);

        existing.setDisplayName(request.getDisplayName());
        existing.setDescription(request.getDescription());
        existing.setExpression(request.getExpression());
        existing.setInvariantType(request.getInvariantType());
        existing.setSeverity(request.getSeverity());
        existing.setScopeType(request.getScopeType());
        existing.setScopeRef(request.getScopeRef());
        existing.setModelCode(request.getModelCode());
        existing.setEnabled(request.isEnabled());
        existing.setUpdatedAt(Instant.now());
        existing.setRowVersion(existing.getRowVersion() + 1);

        invariantMapper.updateById(existing);
        log.info("Updated invariant definition: pid={}", pid);
        return existing;
    }

    @Override
    @Transactional
    public void publish(String pid) {
        InvariantDefinition definition = getByPid(pid);

        if (!Status.DRAFT.getCode().equals(definition.getStatus())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Only DRAFT invariants can be published. Current status: " + definition.getStatus());
        }

        invariantMapper.markAsNotCurrent(definition.getTenantId(), definition.getCode());
        invariantMapper.publishById(definition.getId(), Status.PUBLISHED.getCode());
        log.info("Published invariant: code={}, version={}", definition.getCode(), definition.getVersion());
    }

    @Override
    @Transactional
    public void delete(String pid) {
        InvariantDefinition definition = getByPid(pid);
        invariantMapper.softDelete(pid);
        log.info("Deleted invariant: pid={}, code={}", pid, definition.getCode());
    }

    private void validateRequest(InvariantDefinitionCreateRequest request) {
        if (!VALID_TYPES.contains(request.getInvariantType())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Invalid invariant type: " + request.getInvariantType() + ". Must be PRE, POST, or ALWAYS");
        }
        if (!VALID_SEVERITIES.contains(request.getSeverity())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Invalid severity: " + request.getSeverity() + ". Must be ERROR or WARN");
        }
        if (!VALID_SCOPES.contains(request.getScopeType())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Invalid scope type: " + request.getScopeType() + ". Must be MODEL, COMMAND, or STATE");
        }
    }
}
