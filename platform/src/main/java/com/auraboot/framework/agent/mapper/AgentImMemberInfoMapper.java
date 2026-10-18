package com.auraboot.framework.agent.mapper;

import com.auraboot.framework.im.dto.ConversationMemberInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** Existing ordered human/agent membership projection, including tenant and tombstone predicates. */
@Mapper
public interface AgentImMemberInfoMapper {
    @Select("""
        SELECT m.member_type AS memberType,
               m.member_id AS memberId,
               CASE WHEN m.member_type = 'human' THEN m.member_id ELSE NULL END AS userId,
               CASE
                   WHEN m.member_type = 'agent' THEN a.name
                   ELSE COALESCE(u.nick_name, u.user_name)
               END AS name,
               CASE
                   WHEN m.member_type = 'agent' THEN a.name
                   ELSE COALESCE(u.nick_name, u.user_name)
               END AS displayName,
               CASE
                   WHEN m.member_type = 'agent' THEN a.avatar_url
                   ELSE NULL
               END AS avatarUrl,
               m.role AS role,
               a.agent_code AS agentCode,
               CASE
                   WHEN m.member_type = 'agent' THEN COALESCE(a.expertise, a.agent_type)
                   ELSE NULL
               END AS employeeTitle
        FROM ab_im_conversation_member m
        LEFT JOIN ab_user u ON u.id = m.member_id AND m.member_type = 'human'
        LEFT JOIN ab_agent_definition a
               ON a.id = m.member_id
              AND a.tenant_id = m.tenant_id
              AND m.member_type = 'agent'
              AND (a.deleted_flag = FALSE OR a.deleted_flag IS NULL)
        WHERE m.conversation_id = #{conversationId}
          AND m.tenant_id = #{tenantId}
        ORDER BY m.joined_at ASC
        """)
    List<ConversationMemberInfo> findMembersWithInfo(@Param("conversationId") Long conversationId,
                                                      @Param("tenantId") Long tenantId);
}
