package com.auraboot.framework.openplatform.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface OpenPlatformAuthMapper {
    @Select("""
            SELECT c.id AS credential_id, c.pid AS credential_pid, c.tenant_id,
                   c.installation_id, c.secret_hash, c.status AS credential_status,
                   c.expires_at AS credential_expires_at,
                   i.pid AS installation_pid, i.environment, i.rate_limit_per_minute,
                   i.status AS installation_status,
                   a.pid AS application_pid, a.status AS application_status
            FROM ab_application_credential c
            JOIN ab_application_installation i ON i.id = c.installation_id
            JOIN ab_external_application a ON a.id = i.application_id
            WHERE c.client_id = #{clientId}
            LIMIT 1
            """)
    CredentialAuthRecord findCredential(@Param("clientId") String clientId);

    @Select("""
            SELECT t.pid AS token_pid, t.tenant_id, t.installation_id, t.scopes,
                   t.expires_at AS token_expires_at,
                   i.pid AS installation_pid, i.environment, i.status AS installation_status,
                   a.pid AS application_pid, a.status AS application_status
            FROM ab_application_access_token t
            JOIN ab_application_installation i ON i.id = t.installation_id
            JOIN ab_external_application a ON a.id = i.application_id
            WHERE t.token_hash = #{tokenHash}
              AND t.audience = #{audience}
              AND t.revoked_at IS NULL
              AND t.expires_at > #{now}
            LIMIT 1
            """)
    TokenAuthRecord findToken(@Param("tokenHash") String tokenHash,
                              @Param("audience") String audience,
                              @Param("now") Instant now);

    @Update("""
            UPDATE ab_application_credential
            SET last_used_at = #{now}
            WHERE id = #{credentialId}
            """)
    int touchCredential(@Param("credentialId") Long credentialId, @Param("now") Instant now);

    @Update("""
            UPDATE ab_application_access_token
            SET last_used_at = #{now}
            WHERE pid = #{tokenPid}
            """)
    int touchToken(@Param("tokenPid") String tokenPid, @Param("now") Instant now);

    record CredentialAuthRecord(Long credentialId, String credentialPid, Long tenantId,
                                Long installationId, String secretHash, String credentialStatus,
                                Instant credentialExpiresAt, String installationPid, String environment,
                                String installationStatus, String applicationPid, String applicationStatus) {
    }

    record TokenAuthRecord(String tokenPid, Long tenantId, Long installationId, String scopes,
                           Instant tokenExpiresAt, String installationPid, String environment,
                           Integer rateLimitPerMinute,
                           String installationStatus, String applicationPid, String applicationStatus) {
    }
}
