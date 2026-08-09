package com.auraboot.framework.auth.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import com.auraboot.framework.auth.dto.LoginContextRef;

@Mapper
public interface LoginApplicationChannelMapper {
    @Select("""
            SELECT DISTINCT m.auth_method
              FROM ab_login_application a
              JOIN ab_login_channel c ON c.application_id = a.id
              JOIN ab_login_channel_auth_method m ON m.login_channel_id = c.id
             WHERE a.code = #{applicationCode}
               AND c.code = #{channelCode}
               AND a.status = 'active'
               AND c.status = 'active'
               AND m.status = 'active'
               AND (
                    (#{tenantId,jdbcType=BIGINT} IS NULL AND c.tenant_id IS NULL)
                    OR c.tenant_id = #{tenantId}
                    OR c.tenant_id IS NULL
               )
             ORDER BY m.auth_method
            """)
    @InterceptorIgnore(tenantLine = "true")
    List<String> findEnabledAuthMethods(
            @Param("applicationCode") String applicationCode,
            @Param("channelCode") String channelCode,
            @Param("tenantId") Long tenantId);

    @Select("""
            SELECT a.id AS application_id, c.id AS login_channel_id
              FROM ab_login_application a
              JOIN ab_login_channel c ON c.application_id = a.id
             WHERE a.code = #{applicationCode}
               AND c.code = #{channelCode}
               AND a.status = 'active'
               AND c.status = 'active'
               AND (c.tenant_id = #{tenantId} OR c.tenant_id IS NULL)
             ORDER BY CASE WHEN c.tenant_id = #{tenantId} THEN 0 ELSE 1 END
             LIMIT 1
            """)
    @InterceptorIgnore(tenantLine = "true")
    LoginContextRef resolveLoginContext(
            @Param("applicationCode") String applicationCode,
            @Param("channelCode") String channelCode,
            @Param("tenantId") Long tenantId);
}
