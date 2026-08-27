package com.auraboot.framework.auth.mapper;

import com.auraboot.framework.auth.entity.UserSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {

    @Insert("""
            INSERT INTO ab_user_session (
                pid, user_id, token_hash, application_id, login_channel_id,
                tenant_id, tenant_member_id, execution_scope, actor_party_id,
                party_membership_id, session_stage, context_version, device_info,
                ip_address, user_agent, created_at, last_active_at, revoked
            ) VALUES (
                #{session.pid}, #{session.userId}, #{session.tokenHash},
                #{session.applicationId}, #{session.loginChannelId}, #{session.tenantId},
                #{session.tenantMemberId}, #{session.executionScope}, #{session.actorPartyId},
                #{session.partyMembershipId}, #{session.sessionStage}, #{session.contextVersion},
                #{session.deviceInfo}, #{session.ipAddress}, #{session.userAgent},
                #{session.createdAt}, #{session.lastActiveAt}, #{session.revoked}
            )
            ON CONFLICT (token_hash) DO NOTHING
            """)
    @Options(useGeneratedKeys = true, keyProperty = "session.id")
    int insertIfAbsent(@Param("session") UserSession session);

    @Select("SELECT * FROM ab_user_session WHERE user_id = #{userId} AND revoked = false ORDER BY last_active_at DESC")
    List<UserSession> findActiveByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM ab_user_session WHERE token_hash = #{tokenHash} AND revoked = false LIMIT 1")
    UserSession findByTokenHash(@Param("tokenHash") String tokenHash);

    @Update("UPDATE ab_user_session SET revoked = true, revoked_at = NOW() WHERE id = #{id}")
    int revokeSession(@Param("id") Long id);

    @Update("UPDATE ab_user_session SET revoked = true, revoked_at = NOW() WHERE user_id = #{userId} AND revoked = false")
    int revokeAllSessions(@Param("userId") Long userId);

    @Update("UPDATE ab_user_session SET last_active_at = NOW() WHERE id = #{id}")
    int updateLastActive(@Param("id") Long id);
}
