package com.auraboot.framework.openplatform.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface OpenApiIdempotencyMapper {
    @Insert("""
            INSERT INTO ab_open_api_idempotency
                (tenant_id, installation_id, route_code, idempotency_key, request_hash,
                 status, expires_at, created_at, updated_at)
            VALUES (#{tenantId}, #{installationId}, #{routeCode}, #{key}, #{requestHash},
                    'processing', #{expiresAt}, NOW(), NOW())
            ON CONFLICT (installation_id, route_code, idempotency_key) DO NOTHING
            """)
    int claim(@Param("tenantId") Long tenantId, @Param("installationId") Long installationId,
              @Param("routeCode") String routeCode, @Param("key") String key,
              @Param("requestHash") String requestHash, @Param("expiresAt") Instant expiresAt);

    @Select("""
            SELECT request_hash FROM ab_open_api_idempotency
            WHERE tenant_id = #{tenantId} AND installation_id = #{installationId}
              AND route_code = #{routeCode} AND idempotency_key = #{key}
            """)
    String findRequestHash(@Param("tenantId") Long tenantId,
                           @Param("installationId") Long installationId,
                           @Param("routeCode") String routeCode, @Param("key") String key);

    @Update("""
            UPDATE ab_open_api_idempotency
            SET status = 'completed', response_status = 202,
                response_body = CAST(#{responseBody} AS jsonb), updated_at = NOW()
            WHERE tenant_id = #{tenantId} AND installation_id = #{installationId}
              AND route_code = #{routeCode} AND idempotency_key = #{key}
              AND request_hash = #{requestHash}
            """)
    int complete(@Param("tenantId") Long tenantId, @Param("installationId") Long installationId,
                 @Param("routeCode") String routeCode, @Param("key") String key,
                 @Param("requestHash") String requestHash, @Param("responseBody") String responseBody);
}
