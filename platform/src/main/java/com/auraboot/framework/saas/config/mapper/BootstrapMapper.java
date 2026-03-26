package com.auraboot.framework.saas.config.mapper;

import com.auraboot.framework.saas.config.entity.BootstrapEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BootstrapMapper extends BaseMapper<BootstrapEntity> {
    @Select("SELECT * FROM ab_bootstrap WHERE status IN ('pending', 'running') ORDER BY created_at DESC LIMIT 1")
    BootstrapEntity findActiveBootstrap();
}
