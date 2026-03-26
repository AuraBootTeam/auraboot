package com.auraboot.framework.scheduler.mapper;

import com.auraboot.framework.scheduler.entity.ScheduledTaskLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for ScheduledTaskLog entity.
 *
 * @since 5.1.0
 */
@Mapper
public interface ScheduledTaskLogMapper extends BaseMapper<ScheduledTaskLog> {

    @Select("""
        SELECT * FROM ab_scheduled_task_log
        WHERE task_pid = #{taskPid}
        ORDER BY started_at DESC
        LIMIT #{limit}
        """)
    List<ScheduledTaskLog> findByTaskPid(@Param("taskPid") String taskPid, @Param("limit") int limit);

    @Select("""
        SELECT * FROM ab_scheduled_task_log
        WHERE task_pid = #{taskPid}
        ORDER BY started_at DESC
        LIMIT 1
        """)
    ScheduledTaskLog findLatest(@Param("taskPid") String taskPid);

    @Select("""
        SELECT * FROM ab_scheduled_task_log
        ORDER BY started_at DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<ScheduledTaskLog> findAll(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM ab_scheduled_task_log")
    long countAll();

    @Select("""
        SELECT * FROM ab_scheduled_task_log
        WHERE task_pid = #{taskPid}
        ORDER BY started_at DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<ScheduledTaskLog> findByTaskPidPaged(@Param("taskPid") String taskPid,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM ab_scheduled_task_log WHERE task_pid = #{taskPid}")
    long countByTaskPid(@Param("taskPid") String taskPid);
}
