package com.auraboot.framework.scheduler.mapper;

import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for ScheduledTask entity.
 *
 * @since 5.1.0
 */
@Mapper
public interface ScheduledTaskMapper extends BaseMapper<ScheduledTask> {

    @Select("SELECT * FROM ab_scheduled_task WHERE pid = #{pid}")
    ScheduledTask findByPid(@Param("pid") String pid);

    @Select("SELECT * FROM ab_scheduled_task WHERE enabled = TRUE ORDER BY created_at")
    List<ScheduledTask> findAllEnabled();

    @Select("SELECT * FROM ab_scheduled_task ORDER BY created_at DESC")
    List<ScheduledTask> findAll();

    @Update("""
        UPDATE ab_scheduled_task SET enabled = #{enabled}, updated_at = now()
        WHERE pid = #{pid}
        """)
    int updateEnabled(@Param("pid") String pid, @Param("enabled") boolean enabled);

    @Update("""
        UPDATE ab_scheduled_task SET last_run_at = #{lastRunAt}, next_run_at = #{nextRunAt}, updated_at = now()
        WHERE pid = #{pid}
        """)
    int updateRunTimes(@Param("pid") String pid,
                       @Param("lastRunAt") Instant lastRunAt,
                       @Param("nextRunAt") Instant nextRunAt);

    @Delete("DELETE FROM ab_scheduled_task WHERE pid = #{pid}")
    int deleteByPid(@Param("pid") String pid);
}
