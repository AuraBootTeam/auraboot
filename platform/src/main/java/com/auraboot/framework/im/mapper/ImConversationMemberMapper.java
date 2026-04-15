package com.auraboot.framework.im.mapper;

import com.auraboot.framework.im.dto.ConversationMemberInfo;
import com.auraboot.framework.im.dto.ReadReceiptInfo;
import com.auraboot.framework.im.model.ImConversationMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ImConversationMemberMapper extends BaseMapper<ImConversationMember> {

    @Select("""
        SELECT conversation_id FROM ab_im_conversation_member
        WHERE tenant_id = #{tenantId} AND member_type = #{memberType} AND member_id = #{memberId}
        """)
    List<Long> findConversationIdsByMember(@Param("tenantId") Long tenantId,
                                            @Param("memberType") String memberType,
                                            @Param("memberId") Long memberId);

    @Select("""
        SELECT member_id FROM ab_im_conversation_member
        WHERE conversation_id = #{conversationId} AND tenant_id = #{tenantId}
          AND member_type = 'human'
        """)
    List<Long> findHumanMemberIds(@Param("conversationId") Long conversationId,
                                   @Param("tenantId") Long tenantId);

    @Update("""
        UPDATE ab_im_conversation_member
        SET last_read_seq = #{seq}
        WHERE conversation_id = #{conversationId}
          AND member_type = #{memberType}
          AND member_id = #{memberId}
          AND tenant_id = #{tenantId}
          AND last_read_seq < #{seq}
        """)
    int updateLastReadSeq(@Param("conversationId") Long conversationId,
                          @Param("memberType") String memberType,
                          @Param("memberId") Long memberId,
                          @Param("tenantId") Long tenantId,
                          @Param("seq") Long seq);

    @Update("""
        UPDATE ab_im_conversation_member
        SET last_pull_seq = #{seq}
        WHERE conversation_id = #{conversationId}
          AND member_type = #{memberType}
          AND member_id = #{memberId}
          AND tenant_id = #{tenantId}
          AND last_pull_seq < #{seq}
        """)
    int updateLastPullSeq(@Param("conversationId") Long conversationId,
                          @Param("memberType") String memberType,
                          @Param("memberId") Long memberId,
                          @Param("tenantId") Long tenantId,
                          @Param("seq") Long seq);

    @Select("""
        SELECT * FROM ab_im_conversation_member
        WHERE conversation_id = #{conversationId}
          AND member_type = #{memberType}
          AND member_id = #{memberId}
          AND tenant_id = #{tenantId}
        """)
    ImConversationMember findMember(@Param("conversationId") Long conversationId,
                                    @Param("memberType") String memberType,
                                    @Param("memberId") Long memberId,
                                    @Param("tenantId") Long tenantId);

    @Update("""
        UPDATE ab_im_conversation_member SET hidden = TRUE
        WHERE conversation_id = #{conversationId}
          AND member_type = #{memberType} AND member_id = #{memberId}
          AND tenant_id = #{tenantId}
        """)
    int hideConversation(@Param("conversationId") Long conversationId,
                          @Param("memberType") String memberType,
                          @Param("memberId") Long memberId,
                          @Param("tenantId") Long tenantId);

    @Update("""
        UPDATE ab_im_conversation_member SET hidden = FALSE
        WHERE conversation_id = #{conversationId} AND tenant_id = #{tenantId} AND hidden = TRUE
        """)
    int unhideForAllMembers(@Param("conversationId") Long conversationId,
                             @Param("tenantId") Long tenantId);

    @Select("""
        SELECT conversation_id FROM ab_im_conversation_member
        WHERE tenant_id = #{tenantId} AND member_type = #{memberType} AND member_id = #{memberId}
          AND (hidden = FALSE OR hidden IS NULL)
        """)
    List<Long> findVisibleConversationIdsByMember(@Param("tenantId") Long tenantId,
                                                    @Param("memberType") String memberType,
                                                    @Param("memberId") Long memberId);

    @Select("""
        SELECT m.member_type AS memberType,
               m.member_id AS memberId,
               m.member_id AS userId,
               COALESCE(u.nick_name, u.user_name) AS name,
               COALESCE(u.nick_name, u.user_name) AS displayName,
               NULL AS avatarUrl,
               m.role AS role
        FROM ab_im_conversation_member m
        LEFT JOIN ab_user u ON u.id = m.member_id AND m.member_type = 'human'
        WHERE m.conversation_id = #{conversationId}
          AND m.tenant_id = #{tenantId}
        ORDER BY m.joined_at ASC
        """)
    List<ConversationMemberInfo> findMembersWithInfo(@Param("conversationId") Long conversationId,
                                                      @Param("tenantId") Long tenantId);

    /**
     * Count members who have read up to (or past) a given message seq,
     * excluding a specific member (the sender).
     */
    @Select("""
        SELECT COUNT(*)
        FROM ab_im_conversation_member
        WHERE conversation_id = #{conversationId}
          AND tenant_id = #{tenantId}
          AND last_read_seq >= #{seq}
          AND NOT (member_type = #{excludeMemberType} AND member_id = #{excludeMemberId})
        """)
    int countReadersForSeq(@Param("conversationId") Long conversationId,
                           @Param("tenantId") Long tenantId,
                           @Param("seq") Long seq,
                           @Param("excludeMemberType") String excludeMemberType,
                           @Param("excludeMemberId") Long excludeMemberId);

    /**
     * Count total members excluding a specific member (the sender).
     */
    @Select("""
        SELECT COUNT(*)
        FROM ab_im_conversation_member
        WHERE conversation_id = #{conversationId}
          AND tenant_id = #{tenantId}
          AND NOT (member_type = #{excludeMemberType} AND member_id = #{excludeMemberId})
        """)
    int countMembersExcluding(@Param("conversationId") Long conversationId,
                              @Param("tenantId") Long tenantId,
                              @Param("excludeMemberType") String excludeMemberType,
                              @Param("excludeMemberId") Long excludeMemberId);

    /**
     * Find members who have read up to (or past) a given message seq,
     * excluding the sender. Returns user info for the read-receipt detail sheet.
     */
    @Select("""
        SELECT m.member_id AS userId,
               COALESCE(u.nick_name, u.user_name) AS displayName,
               NULL AS avatarUrl,
               m.joined_at AS readAt
        FROM ab_im_conversation_member m
        LEFT JOIN ab_user u ON u.id = m.member_id AND m.member_type = 'human'
        WHERE m.conversation_id = #{conversationId}
          AND m.tenant_id = #{tenantId}
          AND m.last_read_seq >= #{seq}
          AND NOT (m.member_type = #{excludeMemberType} AND m.member_id = #{excludeMemberId})
        ORDER BY m.last_read_seq DESC
        """)
    List<ReadReceiptInfo> findReadersForSeq(@Param("conversationId") Long conversationId,
                                            @Param("tenantId") Long tenantId,
                                            @Param("seq") Long seq,
                                            @Param("excludeMemberType") String excludeMemberType,
                                            @Param("excludeMemberId") Long excludeMemberId);

    /**
     * Find agent members in a conversation.
     */
    @Select("""
        SELECT * FROM ab_im_conversation_member
        WHERE conversation_id = #{conversationId}
          AND tenant_id = #{tenantId}
          AND member_type = 'agent'
        """)
    List<ImConversationMember> findAgentMembers(@Param("conversationId") Long conversationId,
                                                 @Param("tenantId") Long tenantId);
}
