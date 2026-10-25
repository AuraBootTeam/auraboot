package com.auraboot.framework.agent.integration.observability;

import com.auraboot.framework.agent.trace.entity.GenAiUsageRecord;
import com.auraboot.framework.agent.trace.mapper.GenAiUsageMapper;
import com.auraboot.framework.observability.LlmUsageQuery;
import com.auraboot.framework.observability.dto.LlmUsageView;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

/** Tenant-scoped usage ledger adapter; platform queries never depend on ledger entities. */
@Component
@RequiredArgsConstructor
public class AgentLlmUsageQuery implements LlmUsageQuery {
    private final GenAiUsageMapper mapper;

    @Override
    public List<LlmUsageView> findByTrace(Long tenantId, String traceId) {
        return mapper.selectList(new LambdaQueryWrapper<GenAiUsageRecord>()
                .eq(GenAiUsageRecord::getTenantId, tenantId)
                .eq(GenAiUsageRecord::getTraceId, traceId)).stream().map(row -> {
                    LlmUsageView view = new LlmUsageView();
                    BeanUtils.copyProperties(row, view);
                    return view;
                }).toList();
    }
}
