package com.auraboot.framework.email.mapper;

import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.model.EmailRecordLink;
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
     * <p>JOIN traverses record links → messages so callers get full message
     * objects without a second query.</p>
     */
    @Select("""
        SELECT m.*
        FROM ab_email_record_link l
        JOIN ab_email_message m ON m.id = l.message_id
        WHERE l.tenant_id  = #{tenantId}
          AND l.model_code = #{modelCode}
          AND l.record_id  = #{recordId}
          AND l.message_id IS NOT NULL
        ORDER BY m.gmail_date DESC
        LIMIT  #{limit}
        OFFSET #{offset}
        """)
    List<EmailMessage> findMessagesByRecord(@Param("tenantId") Long tenantId,
                                            @Param("modelCode") String modelCode,
                                            @Param("recordId") String recordId,
                                            @Param("limit") int limit,
                                            @Param("offset") int offset);

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
