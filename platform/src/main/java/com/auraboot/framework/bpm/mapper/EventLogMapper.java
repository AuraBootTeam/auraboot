package com.auraboot.framework.bpm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.auraboot.framework.bpm.entity.EventLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface EventLogMapper extends BaseMapper<EventLogEntity> {
    @Select("SELECT * FROM ab_event_log WHERE event_id = #{eventId}")
    EventLogEntity findByEventId(String eventId);

    @Select("SELECT * FROM ab_event_log WHERE instance_id = #{instanceId} ORDER BY created_at DESC")
    List<EventLogEntity> findByInstanceId(String instanceId);

    @Select("SELECT * FROM ab_event_log WHERE status = 'failed' AND retry_count < 3 ORDER BY created_at ASC LIMIT 100")
    List<EventLogEntity> findRetryableEvents();

    @Update("UPDATE ab_event_log SET status = #{status}, consumed_at = NOW() WHERE event_id = #{eventId}")
    int updateStatus(String eventId, String status);
}
