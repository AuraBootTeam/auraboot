package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.OutboxEvent;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Mapper for outbox event table operations.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface OutboxEventMapper {

    @Insert("INSERT INTO ab_outbox (tenant_id, event_id, event_type, command_code, payload, " +
            "status, retry_count, max_retries, next_retry_at, created_at) " +
            "VALUES (#{tenantId}, #{eventId}, #{eventType}, #{commandCode}, " +
            "#{payload, jdbcType=OTHER, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb, " +
            "#{status}, #{retryCount}, #{maxRetries}, #{nextRetryAt}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertEvent(OutboxEvent event);

    @Select("SELECT * FROM ab_outbox WHERE status IN ('pending', 'processing') " +
            "AND next_retry_at <= NOW() ORDER BY created_at LIMIT #{limit}")
    List<OutboxEvent> findReadyEvents(@Param("limit") int limit);

    @Update("UPDATE ab_outbox SET status = 'processing' WHERE id = #{id} AND status IN ('pending', 'processing')")
    int claimEvent(@Param("id") Long id);

    @Update("UPDATE ab_outbox SET status = 'delivered', delivered_at = NOW() WHERE id = #{id}")
    int markDelivered(@Param("id") Long id);

    @Update("UPDATE ab_outbox SET retry_count = retry_count + 1, " +
            "next_retry_at = #{nextRetryAt}, last_error = #{lastError}, " +
            "status = CASE WHEN retry_count + 1 >= max_retries THEN 'failed' ELSE 'pending' END " +
            "WHERE id = #{id}")
    int incrementRetry(@Param("id") Long id, @Param("nextRetryAt") Instant nextRetryAt,
                       @Param("lastError") String lastError);

    @Select("SELECT status, COUNT(*) as cnt FROM ab_outbox GROUP BY status")
    List<Map<String, Object>> countByStatus();

    @Delete("DELETE FROM ab_outbox WHERE status = 'delivered' AND delivered_at < #{before}")
    int cleanupDelivered(@Param("before") Instant before);
}
