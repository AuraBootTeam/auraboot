package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.DecisionAlarm;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Decision Alarm Mapper.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Mapper
public interface DecisionAlarmMapper {

    @Insert("""
        INSERT INTO ab_decision_alarm
        (tenant_id, alarm_type, subject_type, subject_id, stage, severity, message, status, created_at)
        VALUES
        (#{tenantId}, #{alarmType}, #{subjectType}, #{subjectId}, #{stage},
         #{severity}, #{message}, #{status}, #{createdAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAlarm(DecisionAlarm alarm);

    @Select("""
        SELECT * FROM ab_decision_alarm
        WHERE tenant_id = #{tenantId} AND status = 'open'
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<DecisionAlarm> findOpenAlarms(@Param("tenantId") Long tenantId, @Param("limit") int limit);

    @Select("""
        SELECT * FROM ab_decision_alarm
        WHERE tenant_id = #{tenantId} AND alarm_type = #{alarmType} AND status = 'open'
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<DecisionAlarm> findOpenAlarmsByType(@Param("tenantId") Long tenantId,
                                              @Param("alarmType") String alarmType,
                                              @Param("limit") int limit);

    @Update("UPDATE ab_decision_alarm SET status = 'acknowledged', acknowledged_at = NOW() WHERE id = #{id}")
    int acknowledgeAlarm(@Param("id") Long id);

    @Update("UPDATE ab_decision_alarm SET status = 'resolved', resolved_at = NOW() WHERE id = #{id}")
    int resolveAlarm(@Param("id") Long id);

    @Select("""
        SELECT COUNT(*) FROM ab_decision_alarm
        WHERE tenant_id = #{tenantId} AND alarm_type = #{alarmType}
        AND subject_type = #{subjectType} AND subject_id = #{subjectId} AND stage = #{stage}
        AND status = 'open'
        """)
    int countOpenAlarm(@Param("tenantId") Long tenantId,
                       @Param("alarmType") String alarmType,
                       @Param("subjectType") String subjectType,
                       @Param("subjectId") String subjectId,
                       @Param("stage") String stage);
}
