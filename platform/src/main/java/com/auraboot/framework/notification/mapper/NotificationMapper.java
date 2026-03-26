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

    @Select("""
        SELECT * FROM ab_notification
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
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
        """)
    long countByUser(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Select("""
        SELECT COUNT(*) FROM ab_notification
        WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND is_read = FALSE
        """)
    int countUnread(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Update("""
        UPDATE ab_notification SET is_read = TRUE, read_at = now()
        WHERE tenant_id = #{tenantId} AND id = #{id}
        """)
    int markAsRead(@Param("tenantId") Long tenantId, @Param("id") Long id);

    @Update("""
        UPDATE ab_notification SET is_read = TRUE, read_at = now()
        WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND is_read = FALSE
        """)
    int markAllAsRead(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Select("""
        SELECT user_id FROM ab_notification
        WHERE tenant_id = #{tenantId} AND id = #{id}
        """)
    Long findUserIdById(@Param("tenantId") Long tenantId, @Param("id") Long id);
}
