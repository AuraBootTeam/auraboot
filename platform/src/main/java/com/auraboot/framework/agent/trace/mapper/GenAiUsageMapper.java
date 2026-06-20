package com.auraboot.framework.agent.trace.mapper;

import com.auraboot.framework.agent.trace.entity.GenAiUsageRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for the durable LLM usage/cost ledger (A-G6, P1). BaseMapper.insert
 * auto-maps the {@code @TableName} entity columns.
 */
@Mapper
public interface GenAiUsageMapper extends BaseMapper<GenAiUsageRecord> {
}
