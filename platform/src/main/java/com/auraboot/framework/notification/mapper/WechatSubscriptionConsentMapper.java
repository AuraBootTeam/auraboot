package com.auraboot.framework.notification.mapper;

import org.apache.ibatis.annotations.*;

import java.time.Instant;

/** Global provider-owned facts, scoped by explicit app/openid; no tenant-local copy of subscription quota. */
@Mapper
public interface WechatSubscriptionConsentMapper {
    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(#{principal}, 0))) locked")
    Integer lockPrincipal(@Param("principal") String principal);

    @Insert("""
        INSERT INTO ab_wechat_subscription_event(event_key,app_id,openid,occurred_at,event_kind,items)
        VALUES(#{key},#{app},#{openid},#{occurred},#{kind},
          #{items,jdbcType=OTHER,typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb)
        ON CONFLICT(event_key) DO NOTHING
        """)
    int insertEvent(@Param("key") String key, @Param("app") String app, @Param("openid") String openid,
            @Param("occurred") Instant occurred, @Param("kind") String kind, @Param("items") String items);

    @Insert("""
        INSERT INTO ab_wechat_subscription_state(app_id,openid,template_id,revoked_through)
        VALUES(#{app},#{openid},#{template},#{occurred})
        ON CONFLICT(app_id,openid,template_id) DO UPDATE
          SET revoked_through=GREATEST(ab_wechat_subscription_state.revoked_through,EXCLUDED.revoked_through)
        """)
    int revokeThrough(@Param("app") String app, @Param("openid") String openid,
            @Param("template") String template, @Param("occurred") Instant occurred);

    @Select("""
        SELECT revoked_through FROM ab_wechat_subscription_state
        WHERE app_id=#{app} AND openid=#{openid} AND template_id=#{template}
        """)
    Instant revokedThrough(@Param("app") String app, @Param("openid") String openid, @Param("template") String template);

    @Insert("""
        INSERT INTO ab_wechat_subscription_grant(grant_reference,event_key,app_id,openid,template_id,granted_at,expires_at)
        VALUES(#{reference},#{event},#{app},#{openid},#{template},#{occurred},#{expires})
        """)
    int insertGrant(@Param("reference") String reference, @Param("event") String event, @Param("app") String app,
            @Param("openid") String openid, @Param("template") String template,
            @Param("occurred") Instant occurred, @Param("expires") Instant expires);
}
