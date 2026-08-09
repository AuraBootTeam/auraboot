package com.auraboot.framework.auth.mapper;

import com.auraboot.framework.auth.dto.LoginContextRef;
import com.auraboot.framework.auth.dto.LoginChannelOption;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LoginApplicationChannelMapper {
    @Select("""
            SELECT DISTINCT CASE
                       WHEN p.id IS NOT NULL THEN p.code
                       ELSE m.auth_method
                   END AS auth_method
              FROM ab_login_application a
              JOIN ab_login_channel c ON c.application_id = a.id
              JOIN ab_login_channel_auth_method m ON m.login_channel_id = c.id
              LEFT JOIN ab_identity_provider_instance p
                ON p.application_id = a.id
               AND p.id = m.identity_provider_instance_id
               AND p.status = 'active'
               AND (p.tenant_id IS NULL OR p.tenant_id = #{tenantId})
             WHERE a.code = #{applicationCode}
               AND c.code = #{channelCode}
               AND a.status = 'active'
               AND c.status = 'active'
               AND m.status = 'active'
               AND (m.identity_provider_instance_id IS NULL OR p.id IS NOT NULL)
               AND (
                    (#{tenantId,jdbcType=BIGINT} IS NULL AND c.tenant_id IS NULL)
                    OR c.tenant_id = #{tenantId}
                    OR c.tenant_id IS NULL
               )
             ORDER BY auth_method
            """)
    @InterceptorIgnore(tenantLine = "true")
    List<String> findEnabledAuthMethods(
            @Param("applicationCode") String applicationCode,
            @Param("channelCode") String channelCode,
            @Param("tenantId") Long tenantId);

    @Select("""
            SELECT DISTINCT CASE
                       WHEN p.id IS NOT NULL THEN p.code
                       ELSE m.auth_method
                   END AS code,
                   CASE
                       WHEN p.id IS NOT NULL AND lower(p.provider_type) = 'ldap' THEN 'ldap'
                       WHEN p.id IS NOT NULL THEN 'oauth'
                       WHEN lower(m.auth_method) IN ('email_password', 'password') THEN 'password'
                       WHEN lower(m.auth_method) IN ('sms', 'email_code') THEN 'otp'
                       WHEN lower(m.auth_method) = 'ldap' THEN 'ldap'
                       ELSE 'oauth'
                   END AS kind,
                   COALESCE(p.display_name, m.auth_method) AS display_name,
                   p.provider_type AS provider_type
              FROM ab_login_application a
              JOIN ab_login_channel c ON c.application_id = a.id
              JOIN ab_login_channel_auth_method m ON m.login_channel_id = c.id
              LEFT JOIN ab_identity_provider_instance p
                ON p.application_id = a.id
               AND p.id = m.identity_provider_instance_id
               AND p.status = 'active'
               AND (p.tenant_id IS NULL OR p.tenant_id = #{tenantId})
             WHERE a.code = #{applicationCode}
               AND c.code = #{channelCode}
               AND a.status = 'active'
               AND c.status = 'active'
               AND m.status = 'active'
               AND (m.identity_provider_instance_id IS NULL OR p.id IS NOT NULL)
               AND (
                    (#{tenantId,jdbcType=BIGINT} IS NULL AND c.tenant_id IS NULL)
                    OR c.tenant_id = #{tenantId}
                    OR c.tenant_id IS NULL
               )
             ORDER BY code
            """)
    @InterceptorIgnore(tenantLine = "true")
    List<LoginChannelOption> findEnabledAuthOptions(
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

    @Select("""
            SELECT EXISTS (
                SELECT 1
                  FROM ab_login_application a
                  JOIN ab_login_channel c ON c.application_id = a.id
                 WHERE a.id = #{applicationId}
                   AND c.id = #{loginChannelId}
                   AND (c.tenant_id = #{tenantId} OR c.tenant_id IS NULL)
                   AND a.status = 'active'
                   AND c.status = 'active'
            )
            """)
    @InterceptorIgnore(tenantLine = "true")
    boolean isActiveLoginContext(
            @Param("tenantId") Long tenantId,
            @Param("applicationId") Long applicationId,
            @Param("loginChannelId") Long loginChannelId);

    @Select("""
            SELECT jsonb_array_elements_text(
                       COALESCE(c.settings -> 'allowedPartyCapabilities', '[]'::jsonb)
                   ) AS capability_code
              FROM ab_login_application a
              JOIN ab_login_channel c ON c.application_id = a.id
             WHERE a.id = #{applicationId}
               AND c.id = #{loginChannelId}
               AND (c.tenant_id = #{tenantId} OR c.tenant_id IS NULL)
               AND a.status = 'active'
               AND c.status = 'active'
             ORDER BY capability_code
            """)
    @InterceptorIgnore(tenantLine = "true")
    List<String> findAllowedPartyCapabilities(
            @Param("tenantId") Long tenantId,
            @Param("applicationId") Long applicationId,
            @Param("loginChannelId") Long loginChannelId);
}
