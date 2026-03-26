package com.auraboot.framework.bpm.mapper;

import com.auraboot.framework.bpm.chain.saga.SagaStep;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SagaStepMapper extends BaseMapper<SagaStep> {
}
