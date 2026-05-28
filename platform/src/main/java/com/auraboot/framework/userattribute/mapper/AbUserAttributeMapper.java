package com.auraboot.framework.userattribute.mapper;

import com.auraboot.framework.userattribute.entity.AbUserAttribute;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AbUserAttributeMapper extends BaseMapper<AbUserAttribute> {

    @Select("SELECT * FROM ab_user_attribute "
          + "WHERE tenant_id = #{tenantId} AND user_id = #{userId} "
          + "AND deleted_flag = FALSE")
    List<AbUserAttribute> listByUser(@Param("tenantId") Long tenantId,
                                      @Param("userId") Long userId);

    @Select("SELECT * FROM ab_user_attribute "
          + "WHERE tenant_id = #{tenantId} AND user_id = #{userId} "
          + "AND attribute_code = #{code} AND deleted_flag = FALSE LIMIT 1")
    AbUserAttribute findOne(@Param("tenantId") Long tenantId,
                             @Param("userId") Long userId,
                             @Param("code") String code);
}
