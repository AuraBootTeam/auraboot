package com.auraboot.framework.auth.mapper;

import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthIdentityMapper extends BaseMapper<AuthIdentity> {
}
