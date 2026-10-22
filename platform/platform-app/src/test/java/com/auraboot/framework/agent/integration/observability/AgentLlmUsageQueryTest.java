package com.auraboot.framework.agent.integration.observability;

import com.auraboot.framework.agent.trace.entity.GenAiUsageRecord;
import com.auraboot.framework.agent.trace.mapper.GenAiUsageMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentLlmUsageQueryTest {
    @Test
    void projectionKeepsTheExistingUsageJsonFieldsAndTenantTracePredicates() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                GenAiUsageRecord.class);
        var mapper = mock(GenAiUsageMapper.class);
        var row = new GenAiUsageRecord();
        row.setId(12L); row.setTenantId(7L); row.setTraceId("trace-a");
        row.setProvider("test"); row.setInputTokens(5); row.setOutputTokens(3);
        row.setAmount(new BigDecimal("0.015")); row.setCurrency("USD");
        row.setCreatedAt(Instant.parse("2026-10-18T00:00:00Z"));
        when(mapper.selectList(any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.Wrapper<?> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("tenant_id", "trace_id");
            assertThat(((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) query)
                    .getParamNameValuePairs().values()).contains(7L, "trace-a");
            return List.of(row);
        });
        var projected = new AgentLlmUsageQuery(mapper).findByTrace(7L, "trace-a");
        var json = new ObjectMapper().findAndRegisterModules();
        assertThat(json.<com.fasterxml.jackson.databind.JsonNode>valueToTree(projected.get(0)))
                .isEqualTo(json.<com.fasterxml.jackson.databind.JsonNode>valueToTree(row));
    }
}
