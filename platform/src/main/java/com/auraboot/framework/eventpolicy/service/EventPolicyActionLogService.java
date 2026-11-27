package com.auraboot.framework.eventpolicy.service;

import com.auraboot.framework.eventpolicy.dto.EventPolicyActionLogDTO;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyExecLogEntity;

import java.util.List;

/**
 * Action-execution log queries for controllers (ArchUnit rule 2: controllers
 * must not touch the mapper layer). Also owns the entity→DTO projection.
 */
public interface EventPolicyActionLogService {

    /**
     * Finds action logs by exact/prefix policy code, or by decision-trace /
     * correlation link. Exactly one filter family must be non-null (the
     * controller enforces "at least one filter" before calling).
     */
    List<EventPolicyActionLogDTO> findActionLogs(Long tenantId, String policyCode, String policyCodePrefix,
                                                 String decisionTraceId, String correlationId, int limit);

    /** Entity→DTO projection shared by list and replay paths. */
    EventPolicyActionLogDTO toDto(DrtPolicyExecLogEntity entity);
}
