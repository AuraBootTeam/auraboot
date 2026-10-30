package com.auraboot.framework.notification.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;
import java.time.Instant;

/** Global one-time grant ownership, with explicit tenant scope for every reservation lookup. */
@Mapper
public interface WechatSubscriptionReservationMapper {
    record Reserved(String sourceVersion, String snapshot) {}
    record Available(String reference, Instant expiresAt) {}

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT source_version AS "sourceVersion", snapshot::text AS snapshot
        FROM ab_wechat_subscription_reservation WHERE tenant_id=#{tenant} AND resolution_reference=#{reference}
        """)
    Reserved find(@Param("tenant") long tenant, @Param("reference") String reference);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT g.grant_reference AS reference, g.expires_at AS "expiresAt"
        FROM ab_wechat_subscription_grant g
        LEFT JOIN ab_wechat_subscription_state s USING(app_id,openid,template_id)
        WHERE g.app_id=#{app} AND g.openid=#{openid} AND g.template_id=#{template}
          AND g.expires_at>#{now} AND (s.revoked_through IS NULL OR g.granted_at>s.revoked_through)
          AND NOT EXISTS(SELECT 1 FROM ab_wechat_subscription_reservation r WHERE r.grant_reference=g.grant_reference)
        ORDER BY g.granted_at,g.grant_reference LIMIT 1 FOR UPDATE OF g
        """)
    Available available(@Param("app") String app, @Param("openid") String openid,
            @Param("template") String template, @Param("now") Instant now);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT count(*) FROM ab_wechat_subscription_grant g
        LEFT JOIN ab_wechat_subscription_state s USING(app_id,openid,template_id)
        WHERE g.grant_reference=#{grant} AND g.app_id=#{app} AND g.openid=#{openid} AND g.template_id=#{template}
          AND g.expires_at=#{expires} AND g.expires_at>#{now}
          AND (s.revoked_through IS NULL OR g.granted_at>s.revoked_through)
        """)
    int eligible(@Param("grant") String grant, @Param("app") String app, @Param("openid") String openid,
            @Param("template") String template, @Param("expires") Instant expires, @Param("now") Instant now);

    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
        INSERT INTO ab_wechat_subscription_reservation(tenant_id,resolution_reference,reservation_reference,
          grant_reference,source_version,snapshot,reserved_at)
        VALUES(#{tenant},#{reference},#{reservation},#{grant},#{version},
          #{snapshot,jdbcType=OTHER,typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb,#{at})
        """)
    int append(@Param("tenant") long tenant, @Param("reference") String reference,
            @Param("reservation") String reservation, @Param("grant") String grant,
            @Param("version") String version, @Param("snapshot") String snapshot, @Param("at") Instant at);
}
