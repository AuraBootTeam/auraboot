package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.InvariantEvaluationLog;
import com.auraboot.framework.meta.mapper.InvariantEvaluationLogMapper;
import com.auraboot.framework.meta.service.InvariantInspectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Default {@link InvariantInspectionService}: direct mapper delegation moved
 * from InvariantController. No behavior change.
 */
@Service
@RequiredArgsConstructor
public class InvariantInspectionServiceImpl implements InvariantInspectionService {

    private final InvariantEvaluationLogMapper evaluationLogMapper;

    @Override
    public List<Map<String, Object>> getViolationStats(Long tenantId, Instant since) {
        return evaluationLogMapper.getViolationStats(tenantId, since);
    }

    @Override
    public List<Map<String, Object>> getViolationTrend(Long tenantId, String code, Instant since) {
        return evaluationLogMapper.getViolationTrend(tenantId, code, since);
    }

    @Override
    public List<InvariantEvaluationLog> findRecentViolations(Long tenantId, int limit) {
        return evaluationLogMapper.findRecentViolations(tenantId, limit);
    }
}
