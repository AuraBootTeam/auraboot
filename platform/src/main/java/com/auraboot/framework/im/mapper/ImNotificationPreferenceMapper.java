package com.auraboot.framework.im.mapper;

import com.auraboot.framework.im.model.ImNotificationPreference;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ImNotificationPreferenceMapper extends BaseMapper<ImNotificationPreference> {

    @Select("""
        SELECT * FROM ab_im_notification_preference
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
        ORDER BY model_code NULLS FIRST, operation_type NULLS FIRST
        """)
    List<ImNotificationPreference> findByUser(@Param("tenantId") Long tenantId,
                                                @Param("userId") Long userId);

    @Select("""
        SELECT * FROM ab_im_notification_preference
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
          AND (model_code IS NULL OR model_code = #{modelCode})
          AND (operation_type IS NULL OR operation_type = #{operationType})
        ORDER BY model_code NULLS LAST, operation_type NULLS LAST
        LIMIT 1
        """)
    ImNotificationPreference findMostSpecific(@Param("tenantId") Long tenantId,
                                               @Param("userId") Long userId,
                                               @Param("modelCode") String modelCode,
                                               @Param("operationType") String operationType);
}
