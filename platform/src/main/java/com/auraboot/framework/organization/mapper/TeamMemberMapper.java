package com.auraboot.framework.organization.mapper;

import com.auraboot.framework.organization.entity.TeamMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TeamMemberMapper extends BaseMapper<TeamMember> {

    @Select("""
            SELECT * FROM ab_team_member
            WHERE team_id = #{teamId}
            ORDER BY role ASC, joined_at ASC
            """)
    List<TeamMember> findByTeamId(@Param("teamId") Long teamId);

    @Select("""
            SELECT * FROM ab_team_member
            WHERE user_id = #{userId} AND tenant_id = #{tenantId}
            ORDER BY joined_at ASC
            """)
    List<TeamMember> findByUserIdAndTenantId(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    @Select("""
            SELECT * FROM ab_team_member
            WHERE team_id = #{teamId} AND user_id = #{userId}
            LIMIT 1
            """)
    TeamMember findByTeamIdAndUserId(@Param("teamId") Long teamId, @Param("userId") Long userId);

    @Select("""
            SELECT tm.team_id FROM ab_team_member tm
            JOIN ab_team t ON t.id = tm.team_id
            WHERE tm.user_id = #{userId}
              AND tm.tenant_id = #{tenantId}
              AND (t.deleted_flag = FALSE OR t.deleted_flag IS NULL)
            """)
    List<Long> findTeamIdsByUserIdAndTenantId(@Param("userId") Long userId, @Param("tenantId") Long tenantId);
}
