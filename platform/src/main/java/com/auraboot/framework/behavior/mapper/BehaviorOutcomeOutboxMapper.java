package com.auraboot.framework.behavior.mapper;

import com.auraboot.framework.behavior.outcome.BehaviorOutcomeOutbox;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface BehaviorOutcomeOutboxMapper extends BaseMapper<BehaviorOutcomeOutbox> {

    @Insert("""
            INSERT INTO ab_behavior_outcome_outbox
                (tenant_id, event_id, user_id, event_name, target_type, target_key, payload,
                 trace_id, source_span_id, run_id, interaction_id, caused_by_event_id,
                 occurred_at, status, attempts, next_attempt_at, created_at)
            VALUES
                (#{tenantId}, #{eventId}, #{userId}, #{eventName}, #{targetType}, #{targetKey},
                 #{payload, jdbcType=OTHER, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb,
                 #{traceId}, #{sourceSpanId}, #{runId}, #{interactionId}, #{causedByEventId},
                 #{occurredAt}, #{status}, #{attempts}, #{nextAttemptAt}, #{createdAt})
            ON CONFLICT (tenant_id, event_id) DO NOTHING
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPending(BehaviorOutcomeOutbox row);

    @Select("""
            SELECT *
            FROM ab_behavior_outcome_outbox
            WHERE status = 'pending'
              AND next_attempt_at <= NOW()
            ORDER BY created_at
            LIMIT #{limit}
            """)
    List<BehaviorOutcomeOutbox> findPending(@Param("limit") int limit);

    @Update("""
            UPDATE ab_behavior_outcome_outbox
            SET status = 'publishing'
            WHERE id = #{id}
              AND status = 'pending'
            """)
    int claimPending(@Param("id") Long id);

    @Update("""
            UPDATE ab_behavior_outcome_outbox
            SET status = 'published',
                published_at = NOW(),
                last_error = NULL
            WHERE id = #{id}
              AND status = 'publishing'
            """)
    int markPublished(@Param("id") Long id);

    @Update("""
            UPDATE ab_behavior_outcome_outbox
            SET attempts = attempts + 1,
                status = CASE WHEN attempts + 1 >= 10 THEN 'failed' ELSE 'pending' END,
                next_attempt_at = #{nextAttemptAt},
                last_error = #{lastError}
            WHERE id = #{id}
              AND status = 'publishing'
            """)
    int markFailed(@Param("id") Long id,
                   @Param("nextAttemptAt") Instant nextAttemptAt,
                   @Param("lastError") String lastError);
}
