package com.auraboot.framework.chatbi.v2.mapper;

import com.auraboot.framework.chatbi.v2.entity.ChatBiConversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChatBiConversationMapper extends BaseMapper<ChatBiConversation> {

    @Select("SELECT * FROM chatbi_conversation "
          + "WHERE tenant_id = #{tenantId} AND pid = #{pid} LIMIT 1")
    ChatBiConversation findByPid(@Param("tenantId") Long tenantId,
                                  @Param("pid") String pid);

    @Select("SELECT * FROM chatbi_conversation "
          + "WHERE tenant_id = #{tenantId} AND user_id = #{userId} "
          + "AND status = 'ACTIVE' "
          + "ORDER BY created_at DESC LIMIT #{limit}")
    List<ChatBiConversation> listActiveByUser(@Param("tenantId") Long tenantId,
                                               @Param("userId") Long userId,
                                               @Param("limit") int limit);

    /**
     * Atomically clear the messages_json + stamp context_reset_at. Idempotent:
     * a second call simply moves the reset cursor forward.
     */
    @Update("UPDATE chatbi_conversation "
          + "SET messages_json = '[]'::jsonb, context_reset_at = NOW(), "
          + "    token_budget_used = 0 "
          + "WHERE tenant_id = #{tenantId} AND pid = #{pid}")
    int clearContext(@Param("tenantId") Long tenantId,
                     @Param("pid") String pid);

    /** Sets status=CLOSED. Idempotent. */
    @Update("UPDATE chatbi_conversation SET status = 'CLOSED' "
          + "WHERE tenant_id = #{tenantId} AND pid = #{pid} AND status = 'ACTIVE'")
    int close(@Param("tenantId") Long tenantId,
              @Param("pid") String pid);
}
