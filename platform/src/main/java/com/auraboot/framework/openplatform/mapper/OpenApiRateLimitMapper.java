package com.auraboot.framework.openplatform.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface OpenApiRateLimitMapper {
    @Select(value = """
            WITH token AS MATERIALIZED (
                SELECT t.pid AS token_pid, t.tenant_id, t.installation_id, t.scopes,
                       t.expires_at AS token_expires_at,
                       i.pid AS installation_pid, i.environment, i.rate_limit_per_minute,
                       i.status AS installation_status,
                       a.pid AS application_pid, a.status AS application_status
                FROM ab_application_access_token t
                JOIN ab_application_installation i ON i.id = t.installation_id
                JOIN ab_external_application a ON a.id = i.application_id
                WHERE t.token_hash = #{tokenHash} AND t.audience = #{audience}
                  AND t.revoked_at IS NULL AND t.expires_at > #{usedAt}
                LIMIT 1
            ), consumed AS (
                INSERT INTO ab_open_api_rate_window (installation_id, window_start, request_count)
                SELECT installation_id, #{windowStart}, 1 FROM token
                WHERE installation_status = 'active' AND application_status = 'active'
                  AND scopes @> jsonb_build_array(CAST(#{requiredScope} AS text))
                ON CONFLICT (installation_id, window_start) DO UPDATE
                SET request_count = ab_open_api_rate_window.request_count + 1
                WHERE ab_open_api_rate_window.request_count <
                      (SELECT rate_limit_per_minute FROM token)
                RETURNING request_count
            ), touched AS (
                UPDATE ab_application_access_token
                SET last_used_at = GREATEST(last_used_at, #{usedAt})
                WHERE pid = (SELECT token_pid FROM token) AND EXISTS (SELECT 1 FROM consumed)
                RETURNING pid
            )
            SELECT token.*, (SELECT request_count FROM consumed) AS consumed_count FROM token
            """, affectData = true)
    AuthenticationResult authenticate(@Param("tokenHash") String tokenHash,
                                      @Param("audience") String audience,
                                      @Param("usedAt") Instant usedAt,
                                      @Param("windowStart") Instant windowStart,
                                      @Param("requiredScope") String requiredScope);

    record AuthenticationResult(String tokenPid, Long tenantId, Long installationId,
                                String scopes, Instant tokenExpiresAt, String installationPid,
                                String environment, Integer rateLimitPerMinute,
                                String installationStatus, String applicationPid,
                                String applicationStatus, Integer consumedCount) { }
}
