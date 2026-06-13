package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.entity.DecisionImpactAckEntity;
import com.auraboot.framework.decision.mapper.DecisionImpactAckMapper;
import com.auraboot.framework.decision.service.DecisionImpactAckService;
import com.auraboot.framework.exception.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persists acknowledgement snapshots for guarded DecisionOps changes.
 */
@Service
@RequiredArgsConstructor
public class DecisionImpactAckServiceImpl implements DecisionImpactAckService {

    private final DecisionImpactAckMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void recordAcknowledgement(
            String actionType,
            String targetType,
            String targetCode,
            String targetPid,
            String targetPath,
            String impactSummary,
            Object impactSnapshot,
            String note) {
        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tenantId == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Decision impact acknowledgement tenant not found");
        }
        Instant now = Instant.now();
        DecisionImpactAckEntity entity = new DecisionImpactAckEntity();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(tenantId);
        entity.setActionType(actionType);
        entity.setTargetType(targetType);
        entity.setTargetCode(targetCode);
        entity.setTargetPid(targetPid);
        entity.setTargetPath(targetPath);
        entity.setImpactSummary(impactSummary);
        entity.setImpactSnapshotJson(objectMapper.valueToTree(impactSnapshot));
        entity.setAcknowledgedBy(MetaContext.getCurrentUserPid());
        entity.setAcknowledgedAt(now);
        entity.setNote(note);
        entity.setCreatedAt(now);
        mapper.insert(entity);
    }
}
