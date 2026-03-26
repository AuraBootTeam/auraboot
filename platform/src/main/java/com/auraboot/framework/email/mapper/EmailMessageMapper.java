package com.auraboot.framework.email.mapper;

import com.auraboot.framework.email.model.EmailMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for {@link EmailMessage}.
 *
 * <p>Note: this table has no deleted_flag — hard-delete only.</p>
 *
 * @since 6.5.0
 */
@Mapper
public interface EmailMessageMapper extends BaseMapper<EmailMessage> {

    /**
     * Returns all messages in a Gmail thread, newest first.
     */
    @Select("""
        SELECT * FROM ab_email_message
        WHERE tenant_id      = #{tenantId}
          AND gmail_thread_id = #{threadId}
        ORDER BY gmail_date DESC
        """)
    List<EmailMessage> findByThread(@Param("tenantId") Long tenantId,
                                    @Param("threadId") String threadId);

    /**
     * Checks whether a Gmail message has already been synced for the given account.
     * Used for idempotent sync — avoids duplicate inserts.
     */
    @Select("""
        SELECT COUNT(*) > 0 FROM ab_email_message
        WHERE account_id       = #{accountId}
          AND gmail_message_id = #{gmailMessageId}
        """)
    boolean existsByGmailMessageId(@Param("accountId") Long accountId,
                                   @Param("gmailMessageId") String gmailMessageId);

    /**
     * Counts inbound messages from a specific sender received after {@code since}.
     * Used by the auto-link heuristic to detect warm leads.
     */
    @Select("""
        SELECT COUNT(*) FROM ab_email_message
        WHERE account_id    = #{accountId}
          AND from_address  = #{fromAddress}
          AND direction     = 'inbound'
          AND gmail_date   >= #{since}
        """)
    int countInboundFrom(@Param("accountId") Long accountId,
                         @Param("fromAddress") String fromAddress,
                         @Param("since") Instant since);
}
