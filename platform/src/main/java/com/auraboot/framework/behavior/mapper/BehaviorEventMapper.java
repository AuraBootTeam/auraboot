package com.auraboot.framework.behavior.mapper;

import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Mapper for the behavior analytics event store (M1). */
@Mapper
public interface BehaviorEventMapper extends BaseMapper<BehaviorEvent> {
}
