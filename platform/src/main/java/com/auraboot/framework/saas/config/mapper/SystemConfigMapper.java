package com.auraboot.framework.saas.config.mapper;

import com.auraboot.framework.saas.config.entity.SystemConfigEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfigEntity> {
    @Select("SELECT * FROM ab_system_config WHERE config_key = #{key} LIMIT 1")
    SystemConfigEntity findByKey(@Param("key") String key);
}
