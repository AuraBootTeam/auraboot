package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.entity.InvariantEvaluationLog;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Invariant monitoring reads for controllers (ArchUnit rule 2: controllers
 * must not touch the mapper layer).
 */
public interface InvariantInspectionService {

    List<Map<String, Object>> getViolationStats(Long tenantId, Instant since);

    List<Map<String, Object>> getViolationTrend(Long tenantId, String code, Instant since);

    List<InvariantEvaluationLog> findRecentViolations(Long tenantId, int limit);
}
