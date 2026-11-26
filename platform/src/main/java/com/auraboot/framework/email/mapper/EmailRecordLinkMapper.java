package com.auraboot.framework.email.mapper;

import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.model.EmailRecordLink;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for {@link EmailRecordLink}.
 *
 * @since 6.5.0
 */
@Mapper
public interface EmailRecordLinkMapper extends BaseMapper<EmailRecordLink> {

    /**
     * Returns email messages linked to a CRM record, most recent first,
     * with pagination support.
     *
     * <p>Check mailbox access before pagination and constrain all tenant-owned joins explicitly.</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT m.*
        FROM ab_email_message m
        JOIN ab_email_account a ON a.id = m.account_id AND a.tenant_id = m.tenant_id
        WHERE m.tenant_id = #{tenantId}
          AND a.deleted_flag = FALSE
          AND (
            (a.account_type = 'personal' AND a.user_id = #{userId})
            OR (a.account_type = 'shared' AND (
              a.user_id = #{userId}
              OR EXISTS (SELECT 1 FROM ab_email_account_member am
                         WHERE am.account_id = a.id AND am.user_id = #{userId})
            ))
          )
          AND EXISTS (
            SELECT 1 FROM ab_email_record_link l
            WHERE l.tenant_id = m.tenant_id AND l.message_id = m.id
              AND l.model_code = #{modelCode} AND l.record_pid = #{recordPid}
          )
        ORDER BY m.gmail_date DESC NULLS LAST, m.id DESC
        LIMIT  #{limit}
        OFFSET #{offset}
        """)
    List<EmailMessage> findMessagesByRecord(@Param("tenantId") Long tenantId,
                                            @Param("userId") Long userId,
                                            @Param("modelCode") String modelCode,
                                            @Param("recordPid") String recordPid,
                                            @Param("offset") int offset,
                                            @Param("limit") int limit);

    /**
     * Returns all links for a Gmail thread (used to find which CRM records
     * a thread is associated with).
     */
    @Select("""
        SELECT * FROM ab_email_record_link
        WHERE tenant_id = #{tenantId}
          AND thread_id  = #{threadId}
        """)
    List<EmailRecordLink> findByThread(@Param("tenantId") Long tenantId,
                                       @Param("threadId") String threadId);
}
