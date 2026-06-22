package com.auraboot.framework.behavior.mapper;

import com.auraboot.framework.behavior.entity.BehaviorQuarantine;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Mapper for the behavior ingest quarantine sink (SoT §2.7 quarantine.v1). */
@Mapper
public interface BehaviorQuarantineMapper extends BaseMapper<BehaviorQuarantine> {
}
