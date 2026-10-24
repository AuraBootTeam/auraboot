package com.auraboot.framework.inbox.mapper;

import com.auraboot.framework.inbox.model.InboxItem;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Mapper for ab_inbox_item table.
 *
 * @since 6.3.0
 */
public interface InboxItemMapper extends BaseMapper<InboxItem> {

    String VISIBLE_ACK=com.auraboot.framework.workbench.mapper.AnnouncementProjectionVisibility.INBOX;

    String ACTIVE_ACK = """
        (item_type != 'announcement_ack' OR EXISTS (
          SELECT 1 FROM ab_announcement_recipient ar
          JOIN ab_announcement_recipient_snapshot ars ON ars.tenant_id=ar.tenant_id AND ars.version_pid=ar.version_pid AND ars.sealed=TRUE
          JOIN ab_announcement aa ON aa.tenant_id=ar.tenant_id AND aa.active_version_pid=ar.version_pid AND aa.deleted_flag=FALSE
          JOIN ab_announcement_version_control ac ON ac.tenant_id=ar.tenant_id AND ac.version_pid=ar.version_pid AND ac.publication_state='published'
          JOIN ab_tenant_member am ON am.tenant_id=ar.tenant_id AND am.pid=ar.member_pid AND am.user_id=ab_inbox_item.user_id AND am.status='active' AND am.deleted_flag=FALSE
          JOIN ab_user au ON au.id=am.user_id AND au.pid=ar.user_pid AND au.is_enabled=TRUE AND au.deleted_flag=FALSE
          WHERE ar.tenant_id=ab_inbox_item.tenant_id AND ar.version_pid=ab_inbox_item.source_id AND ar.acknowledged_at IS NULL
            AND (ac.expires_at IS NULL OR ac.expires_at>clock_timestamp()) ))
        """;

    @Select("SELECT count(*) FROM ab_inbox_item WHERE tenant_id=#{tenantId} AND user_id=#{userId} AND status='pending' AND (expires_at IS NULL OR expires_at>clock_timestamp()) AND "+ACTIVE_ACK)
    int countPending(@Param("tenantId") Long tenantId,@Param("userId") Long userId);

    @Select("""
        SELECT COUNT(*) FROM ab_inbox_item
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
          AND status = 'pending' AND is_read = FALSE
        AND
        """ + VISIBLE_ACK)
    int countUnread(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Select("""
        SELECT COUNT(*) FROM ab_inbox_item
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
          AND status = 'pending' AND is_read = FALSE AND item_type = #{itemType}
        AND
        """ + VISIBLE_ACK)
    int countUnreadByType(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                          @Param("itemType") String itemType);

    @Update("""
        UPDATE ab_inbox_item SET is_read = TRUE, read_at = NOW()
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
          AND is_read = FALSE
        AND
        """ + VISIBLE_ACK)
    int markAllRead(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Update("""
        UPDATE ab_inbox_item SET status = 'acted', action_taken = #{action}, acted_at = NOW()
        WHERE id = #{id} AND tenant_id = #{tenantId} AND user_id = #{userId}
          AND status = 'pending' AND item_type != 'announcement_ack'
        """)
    int markActed(@Param("id") Long id, @Param("tenantId") Long tenantId,
                  @Param("userId") Long userId, @Param("action") String action);

    @Update("""
        UPDATE ab_inbox_item
        SET status = CASE WHEN status = 'pending' THEN 'acted' ELSE status END,
            action_taken = #{action}, acted_at = COALESCE(acted_at, NOW())
        WHERE id = #{id} AND tenant_id = #{tenantId} AND user_id = #{userId}
          AND source_id = #{taskId} AND source_type IN ('workflow', 'bpm')
          AND status IN ('pending', 'closed', 'acted')
          AND (action_taken IS NULL OR action_taken = #{action})
        """)
    int recordCompletedWorkflowAction(@Param("id") Long id, @Param("tenantId") Long tenantId,
                                     @Param("userId") Long userId, @Param("taskId") String taskId,
                                     @Param("action") String action);

    @Update("""
        UPDATE ab_inbox_item SET status = 'dismissed', acted_at = NOW()
        WHERE id = #{id} AND tenant_id = #{tenantId} AND user_id = #{userId}
          AND status = 'pending' AND item_type != 'announcement_ack'
        """)
    int dismiss(@Param("id") Long id, @Param("tenantId") Long tenantId,
                @Param("userId") Long userId);

    @Select("""
        SELECT id FROM ab_inbox_item
        WHERE tenant_id = #{tenantId} AND client_item_id = #{clientItemId}
        LIMIT 1
        """)
    Long findByClientItemId(@Param("tenantId") Long tenantId, @Param("clientItemId") String clientItemId);

    /**
     * Batch mark items as read by IDs.
     */
    @Update("""
        <script>
        UPDATE ab_inbox_item SET is_read = TRUE, read_at = NOW()
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
          AND is_read = FALSE
        AND
        """ + VISIBLE_ACK + "\n" + """
          AND id IN
        <foreach item="id" collection="ids" open="(" separator="," close=")">
            #{id}
        </foreach>
        </script>
        """)
    int batchMarkRead(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                       @Param("ids") java.util.List<Long> ids);

    /**
     * Batch mark items as acted.
     */
    @Update("""
        <script>
        UPDATE ab_inbox_item SET status = 'acted', action_taken = #{action}, acted_at = NOW()
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
          AND status = 'pending' AND item_type != 'announcement_ack'
          AND id IN
        <foreach item="id" collection="ids" open="(" separator="," close=")">
            #{id}
        </foreach>
        </script>
        """)
    int batchMarkActed(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                        @Param("ids") java.util.List<Long> ids, @Param("action") String action);

    /**
     * Batch dismiss items.
     */
    @Update("""
        <script>
        UPDATE ab_inbox_item SET status = 'dismissed', acted_at = NOW()
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
          AND status = 'pending' AND item_type != 'announcement_ack'
          AND id IN
        <foreach item="id" collection="ids" open="(" separator="," close=")">
            #{id}
        </foreach>
        </script>
        """)
    int batchDismiss(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                      @Param("ids") java.util.List<Long> ids);

    /**
     * Count pending items by type for a user (for dashboard summary).
     */
    @Select("""
        SELECT COUNT(*) FROM ab_inbox_item
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
          AND status = 'pending' AND item_type = #{itemType}
        AND
        """ + VISIBLE_ACK)
    int countPendingByType(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                            @Param("itemType") String itemType);

    /**
     * Count items created within a date range, filtered by type.
     * Used for trend comparison (current vs previous period).
     */
    @Select("""
        SELECT COUNT(*) FROM ab_inbox_item
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
          AND item_type = #{itemType}
          AND created_at >= #{startTime} AND created_at < #{endTime}
        AND
        """ + VISIBLE_ACK)
    int countByTypeInDateRange(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                               @Param("itemType") String itemType,
                               @Param("startTime") java.time.Instant startTime,
                               @Param("endTime") java.time.Instant endTime);

    /**
     * Count all items created within a date range (all types).
     * Used for total pending trend comparison.
     */
    @Select("""
        SELECT COUNT(*) FROM ab_inbox_item
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
          AND created_at >= #{startTime} AND created_at < #{endTime}
        AND
        """ + VISIBLE_ACK)
    int countAllInDateRange(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                            @Param("startTime") java.time.Instant startTime,
                            @Param("endTime") java.time.Instant endTime);

    /**
     * Mark expired items for every tenant: PENDING items with expires_at in the past → EXPIRED.
     *
     * <p>This is invoked by a platform scheduler without a request tenant. It is
     * deliberately the only cross-tenant inbox update path; ordinary inbox
     * operations above remain protected by the tenant interceptor.</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE ab_inbox_item SET status = 'expired'
        WHERE status = 'pending' AND expires_at IS NOT NULL AND expires_at < NOW()
        """)
    int markExpiredItems();

    /**
     * Delete old acted/dismissed/expired items for every tenant older than N days.
     */
    @InterceptorIgnore(tenantLine = "true")
    @org.apache.ibatis.annotations.Delete("""
        DELETE FROM ab_inbox_item
        WHERE status IN ('acted', 'dismissed', 'expired')
          AND created_at < NOW() - CAST(#{days} || ' days' AS INTERVAL)
        """)
    int deleteOldItems(@Param("days") int days);

    @Update("""
        UPDATE ab_inbox_item SET status = 'closed', acted_at = NOW()
        WHERE client_item_id LIKE #{prefix} AND status != 'closed'
        """)
    int closeByClientItemIdPrefix(@Param("prefix") String prefix);

    /**
     * Close inbox items matching prefix but excluding a specific clientItemId.
     * Used for task_claimed: close other candidates' items but keep claimer's.
     */
    @Update("""
        UPDATE ab_inbox_item SET status = 'closed', action_taken = #{reason}, acted_at = NOW()
        WHERE client_item_id LIKE #{prefix} AND client_item_id != #{excludeClientItemId}
          AND status IN ('pending', 'acted')
        """)
    int closeByClientItemIdPrefixExcluding(@Param("prefix") String prefix,
                                            @Param("excludeClientItemId") String excludeClientItemId,
                                            @Param("reason") String reason);

    @Update("""
        UPDATE ab_inbox_item SET status = 'closed', acted_at = NOW()
        WHERE client_item_id = #{clientItemId} AND status != 'closed'
        """)
    int closeByClientItemId(@Param("clientItemId") String clientItemId);

    /**
     * Close a specific inbox item by clientItemId with a reason.
     * Used for task_revoked: close removed assignee's item with explanation.
     */
    @Update("""
        UPDATE ab_inbox_item SET status = 'closed', action_taken = #{reason}, acted_at = NOW()
        WHERE client_item_id = #{clientItemId} AND status IN ('pending', 'acted')
        """)
    int closeByClientItemIdWithReason(@Param("clientItemId") String clientItemId,
                                      @Param("reason") String reason);
}
