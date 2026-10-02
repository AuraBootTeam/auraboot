package com.auraboot.framework.branding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.auraboot.framework.branding.AuthAppearanceState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuthAppearanceStateMapper extends BaseMapper<AuthAppearanceState> {
    @Select("SELECT * FROM ab_auth_appearance_state WHERE id = 1 FOR UPDATE")
    @ResultMap("mybatis-plus_AuthAppearanceState")
    AuthAppearanceState lockState();
}
