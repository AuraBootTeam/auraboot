package com.auraboot.framework.auth.mapper;

import com.auraboot.framework.auth.dto.FederatedLoginContext;
import com.auraboot.framework.auth.dto.IdentityProviderSummary;
import com.auraboot.framework.auth.entity.IdentityProviderInstance;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface IdentityProviderInstanceMapper extends BaseMapper<IdentityProviderInstance> {

    @Select("""
            SELECT p.pid,
                   p.tenant_id,
                   a.code AS application_code,
                   p.code,
                   p.display_name,
                   p.provider_type,
                   p.status,
                   p.config::text AS config,
                   p.secret_ref,
                   c.code AS channel_code,
                   m.status AS binding_status,
                   m.sort_order,
                   (p.tenant_id = #{tenantId}) AS editable
              FROM ab_identity_provider_instance p
              JOIN ab_login_application a ON a.id = p.application_id
              LEFT JOIN ab_login_channel_auth_method m
                ON m.application_id = p.application_id
               AND m.identity_provider_instance_id = p.id
              LEFT JOIN ab_login_channel c
                ON c.application_id = m.application_id
               AND c.id = m.login_channel_id
             WHERE a.code = #{applicationCode}
               AND (p.tenant_id IS NULL OR p.tenant_id = #{tenantId})
             ORDER BY CASE WHEN p.tenant_id = #{tenantId} THEN 0 ELSE 1 END,
                      COALESCE(m.sort_order, 2147483647), p.code
            """)
    List<IdentityProviderSummary> listManaged(
            @Param("applicationCode") String applicationCode,
            @Param("tenantId") Long tenantId);

    @Select("""
            SELECT p.*
              FROM ab_identity_provider_instance p
              JOIN ab_login_application a ON a.id = p.application_id
             WHERE p.pid = #{pid}
               AND p.tenant_id = #{tenantId}
               AND a.code = #{applicationCode}
             LIMIT 1
            """)
    IdentityProviderInstance findEditable(
            @Param("pid") String pid,
            @Param("applicationCode") String applicationCode,
            @Param("tenantId") Long tenantId);

    @Select("""
            SELECT p.*
              FROM ab_identity_provider_instance p
             WHERE p.pid = #{pid}
               AND p.tenant_id = #{tenantId}
             LIMIT 1
            """)
    IdentityProviderInstance findEditableByPid(
            @Param("pid") String pid,
            @Param("tenantId") Long tenantId);

    @Select("SELECT id FROM ab_login_application WHERE code = #{code} AND status = 'active'")
    Long findApplicationId(@Param("code") String code);

    @Select("""
            SELECT c.id
              FROM ab_login_channel c
             WHERE c.application_id = #{applicationId}
               AND c.code = #{channelCode}
               AND c.status = 'active'
               AND (c.tenant_id = #{tenantId} OR c.tenant_id IS NULL)
             ORDER BY CASE WHEN c.tenant_id = #{tenantId} THEN 0 ELSE 1 END
             LIMIT 1
            """)
    Long findChannelId(
            @Param("applicationId") Long applicationId,
            @Param("channelCode") String channelCode,
            @Param("tenantId") Long tenantId);

    @Select("""
            SELECT id FROM ab_login_channel_auth_method
             WHERE login_channel_id = #{channelId}
               AND identity_provider_instance_id = #{identityProviderInstanceId}
             LIMIT 1
            """)
    Long findBindingId(
            @Param("channelId") Long channelId,
            @Param("identityProviderInstanceId") Long identityProviderInstanceId);

    @Insert("""
            INSERT INTO ab_login_channel_auth_method (
                pid, application_id, login_channel_id,
                identity_provider_instance_id, auth_method, status, sort_order, settings)
            VALUES (
                #{pid}, #{applicationId}, #{channelId},
                #{identityProviderInstanceId}, #{authMethod}, #{status}, #{sortOrder}, '{}'::jsonb)
            """)
    void insertBinding(
            @Param("pid") String pid,
            @Param("applicationId") Long applicationId,
            @Param("channelId") Long channelId,
            @Param("identityProviderInstanceId") Long identityProviderInstanceId,
            @Param("authMethod") String authMethod,
            @Param("status") String status,
            @Param("sortOrder") Integer sortOrder);

    @Update("""
            UPDATE ab_login_channel_auth_method
               SET auth_method = #{authMethod}, status = #{status},
                   sort_order = #{sortOrder}, updated_at = CURRENT_TIMESTAMP
             WHERE id = #{bindingId}
            """)
    void updateBinding(
            @Param("bindingId") Long bindingId,
            @Param("authMethod") String authMethod,
            @Param("status") String status,
            @Param("sortOrder") Integer sortOrder);

    @Update("""
            UPDATE ab_login_channel_auth_method
               SET status = #{status}, updated_at = CURRENT_TIMESTAMP
             WHERE identity_provider_instance_id = #{identityProviderInstanceId}
            """)
    void updateBindingStatus(
            @Param("identityProviderInstanceId") Long identityProviderInstanceId,
            @Param("status") String status);

    @Select("""
            SELECT a.id AS application_id,
                   c.id AS login_channel_id,
                   p.id AS identity_provider_instance_id,
                   COALESCE(p.tenant_id, c.tenant_id, #{tenantId}) AS tenant_id,
                   a.code AS application_code,
                   c.code AS login_channel_code,
                   p.code AS identity_provider_code,
                   p.provider_type,
                   p.config::text AS provider_config,
                   p.secret_ref,
                   c.settings::text AS channel_settings
              FROM ab_login_application a
              JOIN ab_login_channel c
                ON c.application_id = a.id
              JOIN ab_login_channel_auth_method m
                ON m.application_id = a.id
               AND m.login_channel_id = c.id
              JOIN ab_identity_provider_instance p
                ON p.application_id = a.id
               AND p.id = m.identity_provider_instance_id
             WHERE a.code = #{applicationCode}
               AND c.code = #{channelCode}
               AND p.code = #{identityProviderCode}
               AND a.status = 'active'
               AND c.status = 'active'
               AND m.status = 'active'
               AND p.status = 'active'
               AND (c.tenant_id IS NULL OR c.tenant_id = #{tenantId})
               AND (p.tenant_id IS NULL OR p.tenant_id = #{tenantId})
             ORDER BY CASE WHEN c.tenant_id = #{tenantId} THEN 0 ELSE 1 END,
                      CASE WHEN p.tenant_id = #{tenantId} THEN 0 ELSE 1 END
             LIMIT 1
            """)
    FederatedLoginContext resolveFederatedContext(
            @Param("applicationCode") String applicationCode,
            @Param("channelCode") String channelCode,
            @Param("identityProviderCode") String identityProviderCode,
            @Param("tenantId") Long tenantId);
}
