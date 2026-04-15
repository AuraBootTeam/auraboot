package com.auraboot.framework.im.mapper;

import com.auraboot.framework.im.dto.MessageSearchResult;
import com.auraboot.framework.im.model.ImMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ImMessageMapper extends BaseMapper<ImMessage> {

    @Select("""
        SELECT * FROM ab_im_message
        WHERE conversation_id = #{conversationId}
          AND tenant_id = #{tenantId}
          AND seq > #{afterSeq}
        ORDER BY seq ASC
        LIMIT #{limit}
        """)
    List<ImMessage> findAfterSeq(@Param("conversationId") Long conversationId,
                                  @Param("tenantId") Long tenantId,
                                  @Param("afterSeq") Long afterSeq,
                                  @Param("limit") int limit);

    @Select("""
        SELECT * FROM ab_im_message
        WHERE conversation_id = #{conversationId}
          AND tenant_id = #{tenantId}
          AND seq < #{beforeSeq}
        ORDER BY seq DESC
        LIMIT #{limit}
        """)
    List<ImMessage> findBeforeSeq(@Param("conversationId") Long conversationId,
                                   @Param("tenantId") Long tenantId,
                                   @Param("beforeSeq") Long beforeSeq,
                                   @Param("limit") int limit);

    @Select("""
        SELECT * FROM ab_im_message
        WHERE conversation_id = #{conversationId}
          AND tenant_id = #{tenantId}
          AND client_msg_id = #{clientMsgId}
        """)
    ImMessage findByClientMsgId(@Param("conversationId") Long conversationId,
                                 @Param("tenantId") Long tenantId,
                                 @Param("clientMsgId") String clientMsgId);

    @Update("""
        UPDATE ab_im_message SET recalled = TRUE, content = NULL, card_payload = NULL,
            attachments = NULL, mentions = NULL
        WHERE id = #{messageId} AND tenant_id = #{tenantId} AND sender_id = #{senderId}
            AND recalled = FALSE
        """)
    int recallMessage(@Param("messageId") Long messageId,
                      @Param("tenantId") Long tenantId,
                      @Param("senderId") Long senderId);

    @Select("""
        <script>
        SELECT m.id AS messageId, m.conversation_id AS conversationId,
               c.name AS conversationName, c.type AS conversationType,
               COALESCE(u.nick_name, u.user_name) AS senderName,
               m.content, m.seq, m.created_at AS createdAt
        FROM ab_im_message m
        JOIN ab_im_conversation c ON c.id = m.conversation_id
        LEFT JOIN ab_user u ON u.id = m.sender_id
        WHERE m.tenant_id = #{tenantId}
          AND m.content ILIKE #{keyword}
          AND (m.recalled IS NULL OR m.recalled = FALSE)
          AND m.conversation_id IN
            <foreach item="id" collection="conversationIds" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY m.created_at DESC
        LIMIT #{limit}
        </script>
        """)
    List<MessageSearchResult> searchMessages(@Param("tenantId") Long tenantId,
                                              @Param("conversationIds") List<Long> conversationIds,
                                              @Param("keyword") String keyword,
                                              @Param("limit") int limit);
}
