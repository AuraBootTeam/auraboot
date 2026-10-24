package com.auraboot.framework.notification.mapper;

import com.auraboot.framework.notification.entity.Notification;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for Notification entity.
 *
 * @since 5.1.0
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    String VISIBLE=com.auraboot.framework.workbench.mapper.AnnouncementProjectionVisibility.NOTIFICATION;

    /** Explicit tenant membership and current account validity; never accept global numeric IDs. */
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT u.id FROM ab_user u JOIN ab_tenant_member tm ON tm.user_id = u.id
        WHERE tm.tenant_id = #{tenantId} AND tm.status = 'active' AND tm.deleted_flag = FALSE
          AND u.pid = #{userPid} AND u.deleted_flag = FALSE
          AND u.is_enabled = TRUE AND u.is_account_non_expired = TRUE
          AND u.is_account_non_locked = TRUE AND u.is_credentials_non_expired = TRUE
          AND u.deactivation_status IS NULL
        """)
    Long findActiveRecipient(@Param("tenantId") long tenantId, @Param("userPid") String userPid);

    @Select("""
        SELECT * FROM ab_notification
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
        AND
        """ + VISIBLE + "\n" + """
        ORDER BY created_at DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<Notification> findByUser(@Param("tenantId") Long tenantId,
                                  @Param("userId") Long userId,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);

    @Select("""
        SELECT * FROM ab_notification
        WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND is_read = FALSE
        AND
        """ + VISIBLE + "\n" + """
        ORDER BY created_at DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<Notification> findUnreadByUser(@Param("tenantId") Long tenantId,
                                        @Param("userId") Long userId,
                                        @Param("limit") int limit,
                                        @Param("offset") int offset);

    @Select("""
        SELECT COUNT(*) FROM ab_notification
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
        AND
        """ + VISIBLE)
    long countByUser(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Select("""
        SELECT COUNT(*) FROM ab_notification
        WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND is_read = FALSE
        AND
        """ + VISIBLE)
    int countUnread(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Update("""
        UPDATE ab_notification SET is_read = TRUE, read_at = now()
        WHERE tenant_id = #{tenantId} AND id = #{id} AND user_id = #{userId}
        AND
        """ + VISIBLE)
    int markAsRead(@Param("tenantId") Long tenantId, @Param("id") Long id, @Param("userId") Long userId);

    @Update("""
        UPDATE ab_notification SET is_read = TRUE, read_at = now()
        WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND is_read = FALSE
        AND
        """ + VISIBLE)
    int markAllAsRead(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Select("""
        SELECT user_id FROM ab_notification
        WHERE tenant_id = #{tenantId} AND id = #{id}
        AND
        """ + VISIBLE)
    Long findUserIdById(@Param("tenantId") Long tenantId, @Param("id") Long id);
}
