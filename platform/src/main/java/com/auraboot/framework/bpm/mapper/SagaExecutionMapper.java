package com.auraboot.framework.bpm.mapper;

import com.auraboot.framework.bpm.chain.saga.SagaExecution;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SagaExecutionMapper extends BaseMapper<SagaExecution> {
}
