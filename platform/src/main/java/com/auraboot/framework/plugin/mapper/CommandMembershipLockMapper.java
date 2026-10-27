package com.auraboot.framework.plugin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/** Exact public-pair lookup and side-effect-free locks on the platform identity rows. */
@Mapper
public interface CommandMembershipLockMapper {
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT u.id AS user_id,u.pid AS user_pid,u.user_name,
                   COALESCE(u.nick_name,u.user_name) AS display_name,
                   u.is_enabled,u.deleted_flag AS user_deleted,
                   m.id AS member_id,m.pid AS member_pid,m.status,m.leave_date,
                   m.deleted_flag AS member_deleted
            FROM ab_user u JOIN ab_tenant_member m ON m.user_id=u.id
            WHERE m.tenant_id=#{tenant} AND u.pid=#{user} AND m.pid=#{member}
            """)
    Map<String,Object> exact(@Param("tenant") Long tenant, @Param("user") String user, @Param("member") String member);

    @Select("SELECT id FROM ab_user WHERE id=#{id} FOR UPDATE")
    Long lockUser(@Param("id") Long id);

    @Select("SELECT id FROM ab_tenant_member WHERE tenant_id=#{tenant} AND id=#{id} FOR UPDATE")
    Long lockMember(@Param("tenant") Long tenant, @Param("id") Long id);
}
