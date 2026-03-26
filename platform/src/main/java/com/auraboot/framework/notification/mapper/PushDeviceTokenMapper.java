package com.auraboot.framework.notification.mapper;

import com.auraboot.framework.notification.model.PushDeviceToken;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for push device token management.
 *
 * @since 6.4.0
 */
@Mapper
public interface PushDeviceTokenMapper extends BaseMapper<PushDeviceToken> {

    @Select("""
        SELECT * FROM ab_push_device_token
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND is_valid = TRUE
          AND (deleted_flag = FALSE OR deleted_flag IS NULL)
        ORDER BY last_used_at DESC NULLS LAST
        """)
    List<PushDeviceToken> findValidTokensByUserId(@Param("tenantId") Long tenantId,
                                                   @Param("userId") Long userId);

    @Update("""
        UPDATE ab_push_device_token
        SET is_valid = FALSE, updated_at = NOW()
        WHERE id = #{id}
        """)
    int invalidateToken(@Param("id") Long id);

    /**
     * Find by push_token including soft-deleted records (for upsert/reactivation).
     * Bypasses @TableLogic filtering.
     */
    @Select("""
        SELECT * FROM ab_push_device_token
        WHERE tenant_id = #{tenantId}
          AND push_token = #{pushToken}
        ORDER BY updated_at DESC
        LIMIT 1
        """)
    PushDeviceToken findByPushTokenIncludeDeleted(@Param("tenantId") Long tenantId,
                                                   @Param("pushToken") String pushToken);
}
