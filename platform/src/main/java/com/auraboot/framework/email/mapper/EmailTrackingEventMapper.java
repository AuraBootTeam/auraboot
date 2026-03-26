package com.auraboot.framework.email.mapper;

import com.auraboot.framework.email.model.EmailTrackingEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Mapper for {@link EmailTrackingEvent}.
 *
 * @since 6.5.0
 */
@Mapper
public interface EmailTrackingEventMapper extends BaseMapper<EmailTrackingEvent> {

    /**
     * Counts tracking events of a given type for a message.
     * Commonly called with eventType='open' or eventType='click'.
     */
    @Select("""
        SELECT COUNT(*) FROM ab_email_tracking_event
        WHERE message_id = #{messageId}
          AND event_type  = #{eventType}
        """)
    int countByType(@Param("messageId") Long messageId,
                    @Param("eventType") String eventType);
}
