package com.auraboot.framework.behavior.mapper;

import com.auraboot.framework.behavior.dto.BehaviorDailyPoint;
import com.auraboot.framework.behavior.dto.BehaviorEventCount;
import com.auraboot.framework.behavior.dto.BehaviorOverview;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.time.Instant;

/** Mapper for the behavior analytics event store (M1) + analysis rollups. */
@Mapper
public interface BehaviorEventMapper extends BaseMapper<BehaviorEvent> {

    /** Tenant overview: total events, PV (page_view), UV (distinct user_id|anon_id), sessions. */
    @Select("""
            SELECT count(*)                                                   AS total_events,
                   count(*) FILTER (WHERE event_name = 'page_view')           AS page_views,
                   count(DISTINCT CASE WHEN user_id IS NOT NULL THEN 'user:' || CAST(user_id AS text) ELSE 'anon:' || NULLIF(anon_id, '') END)   AS unique_visitors,
                   count(DISTINCT jsonb_build_array(CASE WHEN user_id IS NOT NULL THEN 'user:' || CAST(user_id AS text) ELSE 'anon:' || NULLIF(anon_id, '') END, client_session_id)) FILTER (WHERE NULLIF(client_session_id, '') IS NOT NULL AND (user_id IS NOT NULL OR NULLIF(anon_id, '') IS NOT NULL))                          AS sessions
            FROM ab_behavior_event
            WHERE tenant_id = #{tenantId}
              AND COALESCE(occurred_at, created_at) >= #{from}
              AND COALESCE(occurred_at, created_at) < #{to}
            """)
    BehaviorOverview overview(@Param("tenantId") Long tenantId, @Param("from") Instant from, @Param("to") Instant to);

    /** Top events by name for a tenant (analysis: which actions/pages dominate). */
    @Select("""
            SELECT event_name, event_category, count(*) AS count
            FROM ab_behavior_event
            WHERE tenant_id = #{tenantId}
              AND COALESCE(occurred_at, created_at) >= #{from}
              AND COALESCE(occurred_at, created_at) < #{to}
            GROUP BY event_name, event_category
            ORDER BY count DESC
            LIMIT 20
            """)
    List<BehaviorEventCount> topEvents(@Param("tenantId") Long tenantId, @Param("from") Instant from, @Param("to") Instant to);

    /** Daily PV/UV/total time series for a tenant (analysis: dashboard trend). */
    @Select("""
            SELECT to_char(date_trunc('day', COALESCE(occurred_at, created_at) AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS day,
                   count(*)                                                  AS total_events,
                   count(*) FILTER (WHERE event_name = 'page_view')          AS page_views,
                   count(DISTINCT CASE WHEN user_id IS NOT NULL THEN 'user:' || CAST(user_id AS text) ELSE 'anon:' || NULLIF(anon_id, '') END)  AS unique_visitors
            FROM ab_behavior_event
            WHERE tenant_id = #{tenantId}
              AND COALESCE(occurred_at, created_at) >= #{from}
              AND COALESCE(occurred_at, created_at) < #{to}
            GROUP BY 1
            ORDER BY 1
            """)
    List<BehaviorDailyPoint> dailyTrend(@Param("tenantId") Long tenantId, @Param("from") Instant from, @Param("to") Instant to);

    @Select("""
            SELECT id
            FROM ab_behavior_event
            WHERE tenant_id = #{tenantId}
              AND event_id = #{eventId}
            LIMIT 1
            """)
    Long findIdByTenantAndEventId(@Param("tenantId") Long tenantId, @Param("eventId") String eventId);
}
