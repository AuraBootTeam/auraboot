package com.auraboot.framework.decision.mapper;

import com.auraboot.framework.decision.entity.DecisionImpactAckEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for DecisionOps blast-radius acknowledgement audit rows.
 */
@Mapper
public interface DecisionImpactAckMapper extends BaseMapper<DecisionImpactAckEntity> {
}
