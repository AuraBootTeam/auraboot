package com.auraboot.framework.inbox.mapper;

import com.auraboot.framework.inbox.model.InboxItem;
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

    @Select("""
        SELECT COUNT(*) FROM ab_inbox_item
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
          AND status = 'pending' AND is_read = FALSE
        """)
    int countUnread(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Select("""
        SELECT COUNT(*) FROM ab_inbox_item
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
          AND status = 'pending' AND is_read = FALSE AND item_type = #{itemType}
        """)
    int countUnreadByType(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                          @Param("itemType") String itemType);

    @Update("""
        UPDATE ab_inbox_item SET is_read = TRUE, read_at = NOW()
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
          AND is_read = FALSE
        """)
    int markAllRead(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Update("""
        UPDATE ab_inbox_item SET status = 'acted', action_taken = #{action}, acted_at = NOW()
        WHERE id = #{id} AND tenant_id = #{tenantId} AND user_id = #{userId}
          AND status = 'pending'
        """)
    int markActed(@Param("id") Long id, @Param("tenantId") Long tenantId,
                  @Param("userId") Long userId, @Param("action") String action);

    @Update("""
        UPDATE ab_inbox_item SET status = 'dismissed', acted_at = NOW()
        WHERE id = #{id} AND tenant_id = #{tenantId} AND user_id = #{userId}
          AND status = 'pending'
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
          AND status = 'pending'
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
          AND status = 'pending'
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
        """)
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
        """)
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
        """)
    int countAllInDateRange(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                            @Param("startTime") java.time.Instant startTime,
                            @Param("endTime") java.time.Instant endTime);

    /**
     * Mark expired items: PENDING items with expires_at in the past → EXPIRED.
     */
    @Update("""
        UPDATE ab_inbox_item SET status = 'expired'
        WHERE status = 'pending' AND expires_at IS NOT NULL AND expires_at < NOW()
        """)
    int markExpiredItems();

    /**
     * Delete old acted/dismissed/expired items older than N days.
     */
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
