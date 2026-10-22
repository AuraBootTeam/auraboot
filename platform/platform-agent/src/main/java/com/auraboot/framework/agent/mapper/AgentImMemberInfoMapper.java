package com.auraboot.framework.agent.mapper;

import com.auraboot.framework.im.dto.ConversationMemberInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** Agent details supplement the membership projection owned by IM. */
@Mapper
public interface AgentImMemberInfoMapper {
    @Select("""
        <script>
        SELECT id AS memberId, name, name AS displayName, avatar_url AS avatarUrl,
               agent_code AS agentCode, COALESCE(expertise, agent_type) AS employeeTitle
        FROM ab_agent_definition
        WHERE tenant_id = #{tenantId}
          AND (deleted_flag = FALSE OR deleted_flag IS NULL)
          AND id IN
          <foreach item='id' collection='agentIds' open='(' separator=',' close=')'>#{id}</foreach>
        </script>
        """)
    List<ConversationMemberInfo> findAgentDetails(@Param("agentIds") List<Long> agentIds,
                                                 @Param("tenantId") Long tenantId);
}
