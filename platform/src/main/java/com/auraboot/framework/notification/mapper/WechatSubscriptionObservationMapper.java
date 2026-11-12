package com.auraboot.framework.notification.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;

/** Atomic append/replay contract, explicitly scoped by the current authenticated principal at the service boundary. */
@Mapper
public interface WechatSubscriptionObservationMapper {
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
        INSERT INTO ab_wechat_subscription_observation(tenant_id,user_id,app_id,identity_pid,idempotency_key,request_hash,results)
        VALUES(#{tenant},#{user},#{app},#{identity},#{key},#{hash},
          #{results,jdbcType=OTHER,typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb)
        ON CONFLICT(tenant_id,user_id,app_id,idempotency_key) DO NOTHING
        """)
    int append(@Param("tenant") long tenant, @Param("user") long user, @Param("app") String app,
            @Param("identity") String identity, @Param("key") String key,
            @Param("hash") String hash, @Param("results") String results);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT request_hash FROM ab_wechat_subscription_observation
        WHERE tenant_id=#{tenant} AND user_id=#{user} AND app_id=#{app} AND idempotency_key=#{key}
          AND identity_pid=#{identity}
        """)
    String storedHash(@Param("tenant") long tenant, @Param("user") long user, @Param("app") String app,
            @Param("identity") String identity, @Param("key") String key);
}
