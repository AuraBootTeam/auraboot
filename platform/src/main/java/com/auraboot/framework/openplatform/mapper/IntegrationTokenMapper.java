package com.auraboot.framework.openplatform.mapper;

import com.auraboot.framework.openplatform.entity.ApplicationAccessToken;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface IntegrationTokenMapper {
    @Select("""
            SELECT i.id AS installation_id, a.pid AS app_id,
                   i.status AS installation_status, a.status AS application_status
            FROM ab_application_installation i
            JOIN ab_external_application a ON a.id = i.application_id AND a.owner_tenant_id = i.tenant_id
            WHERE i.tenant_id = #{tenantId} AND i.pid = #{pid}
            FOR UPDATE OF i
            """)
    InstallationIdentity lockInstallation(@Param("tenantId") Long tenantId, @Param("pid") String pid);

    @Select("""
            SELECT pid, tenant_id, installation_id, token_kind, token_name, never_expires,
                   issued_at, expires_at, last_used_at, revoked_at, accept_until, rotated_at, replaced_by_pid
            FROM ab_application_access_token
            WHERE tenant_id = #{tenantId} AND installation_id = #{installationId} AND token_kind = 'integration'
            ORDER BY issued_at DESC, pid
            """)
    List<ApplicationAccessToken> list(@Param("tenantId") Long tenantId, @Param("installationId") Long installationId);

    @Select("""
            SELECT count(*) FROM ab_application_access_token
            WHERE tenant_id = #{tenantId} AND installation_id = #{installationId} AND management_key = #{key}
              AND token_kind = 'integration'
            """)
    int countOperation(@Param("tenantId") Long tenantId, @Param("installationId") Long installationId, @Param("key") String key);

    @Update("""
            UPDATE ab_application_access_token
            SET rotated_at = #{now}, accept_until = #{until}, replaced_by_pid = #{replacement}
            WHERE tenant_id = #{tenantId} AND installation_id = #{installationId} AND pid = #{pid}
              AND token_kind = 'integration' AND revoked_at IS NULL AND rotated_at IS NULL
              AND (never_expires OR expires_at > #{now})
            """)
    int rotate(@Param("tenantId") Long tenantId, @Param("installationId") Long installationId,
               @Param("pid") String pid, @Param("now") Instant now, @Param("until") Instant until,
               @Param("replacement") String replacement);

    @Update("""
            UPDATE ab_application_access_token SET revoked_at = #{now}
            WHERE tenant_id = #{tenantId} AND installation_id = #{installationId} AND pid = #{pid}
              AND token_kind = 'integration' AND revoked_at IS NULL
            """)
    int revoke(@Param("tenantId") Long tenantId, @Param("installationId") Long installationId,
               @Param("pid") String pid, @Param("now") Instant now);

    record InstallationIdentity(Long installationId, String appId, String installationStatus, String applicationStatus) { }
}
