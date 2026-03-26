package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.IdempotencyRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/**
 * Idempotency Record Mapper
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecord> {

    @Select("SELECT * FROM ab_idempotency_record WHERE tenant_id = #{tenantId} AND client_request_id = #{clientRequestId} AND expires_at > NOW()")
    IdempotencyRecord findByClientRequestId(@Param("tenantId") Long tenantId, @Param("clientRequestId") String clientRequestId);

    @Insert("""
        INSERT INTO ab_idempotency_record
        (tenant_id, client_request_id, request_hash, command_code, outcome, status, expires_at, created_at)
        VALUES
        (#{tenantId}, #{clientRequestId}, #{requestHash}, #{commandCode},
         #{outcome, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{status}, #{expiresAt}, #{createdAt})
        ON CONFLICT (tenant_id, client_request_id) DO NOTHING
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIdempotent(IdempotencyRecord record);

    @Delete("DELETE FROM ab_idempotency_record WHERE expires_at < NOW()")
    int deleteExpired();
}
