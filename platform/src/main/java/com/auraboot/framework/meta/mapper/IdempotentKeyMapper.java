package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.IdempotentKey;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/**
 * Idempotent Key Mapper for AOP-based generic idempotency.
 *
 * Uses PostgreSQL ON CONFLICT for atomic check-and-insert concurrency control.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Mapper
public interface IdempotentKeyMapper extends BaseMapper<IdempotentKey> {

    /**
     * Find a non-expired key by idempotent key and tenant.
     * TenantLineInterceptor does NOT apply to raw @Select, so tenant_id is explicit.
     */
    @Select("""
        SELECT * FROM ab_idempotent_key
        WHERE idempotent_key = #{idempotentKey}
          AND tenant_id = #{tenantId}
          AND expired_at > NOW()
        """)
    IdempotentKey findByKey(@Param("idempotentKey") String idempotentKey, @Param("tenantId") Long tenantId);

    /**
     * Atomically insert a new key. Returns 0 if key already exists (ON CONFLICT DO NOTHING).
     * This provides database-level concurrency control without distributed locks.
     */
    @Insert("""
        INSERT INTO ab_idempotent_key
        (idempotent_key, tenant_id, command_code, request_hash, status, expired_at, created_at, created_by)
        VALUES
        (#{idempotentKey}, #{tenantId}, #{commandCode}, #{requestHash}, #{status}, #{expiredAt}, #{createdAt}, #{createdBy})
        ON CONFLICT (idempotent_key, tenant_id) DO NOTHING
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIfAbsent(IdempotentKey record);

    /**
     * Update status and response data after successful execution.
     */
    @Update("""
        UPDATE ab_idempotent_key
        SET status = #{status},
            response_data = #{responseData, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}
        WHERE idempotent_key = #{idempotentKey}
          AND tenant_id = #{tenantId}
        """)
    int updateStatusAndResponse(@Param("idempotentKey") String idempotentKey,
                                @Param("tenantId") Long tenantId,
                                @Param("status") String status,
                                @Param("responseData") String responseData);

    /**
     * Mark key as EXPIRED to allow retry on failure.
     */
    @Update("""
        UPDATE ab_idempotent_key
        SET status = 'expired'
        WHERE idempotent_key = #{idempotentKey}
          AND tenant_id = #{tenantId}
          AND status = 'processing'
        """)
    int markExpired(@Param("idempotentKey") String idempotentKey, @Param("tenantId") Long tenantId);

    /**
     * Delete all expired records for scheduled cleanup.
     */
    @Delete("DELETE FROM ab_idempotent_key WHERE expired_at < NOW()")
    int deleteExpired();
}
