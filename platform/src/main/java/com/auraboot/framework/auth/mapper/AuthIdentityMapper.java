package com.auraboot.framework.auth.mapper;

import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuthIdentityMapper extends BaseMapper<AuthIdentity> {
    /** Serialize same-identity provisioning until the surrounding login transaction commits. */
    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(#{key}, 0))) locked")
    Integer lockWechatIdentity(@Param("key") String key);
}
