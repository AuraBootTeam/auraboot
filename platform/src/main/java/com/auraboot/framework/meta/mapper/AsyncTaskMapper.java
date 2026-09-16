package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.AsyncTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for ab_async_task table.
 */
@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTask> {

    @Select("SELECT * FROM ab_async_task WHERE task_code = #{taskCode}")
    AsyncTask findByTaskCode(@Param("taskCode") String taskCode);

    @Select("SELECT * FROM ab_async_task WHERE status = 'pending' ORDER BY priority ASC, created_at ASC LIMIT #{limit}")
    List<AsyncTask> findPendingTasks(@Param("limit") int limit);

    @Update("UPDATE ab_async_task SET status='running', started_at=#{startedAt} WHERE id=#{id} AND tenant_id=#{tenantId} AND status='pending'")
    int claimPending(@Param("id") Long id, @Param("tenantId") Long tenantId,
                     @Param("startedAt") Instant startedAt);

    @Update("UPDATE ab_async_task SET status='pending', started_at=NULL WHERE status='running' AND task_type='command-handler' AND input_params->>'resumeOnRestart'='true'")
    int requeueResumableTasksOnStartup();

    @Select("SELECT * FROM ab_async_task WHERE status='pending' AND task_type='command-handler' AND input_params->>'resumeOnRestart'='true' ORDER BY priority, created_at LIMIT #{limit}")
    List<AsyncTask> findResumablePendingTasks(@Param("limit") int limit);

    @Update("UPDATE ab_async_task SET progress = #{progress}, progress_message = #{progressMessage} WHERE id = #{id}")
    int updateProgress(@Param("id") Long id, @Param("progress") int progress,
                       @Param("progressMessage") String progressMessage);

    @Update("UPDATE ab_async_task SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("""
            UPDATE ab_async_task
            SET status = 'failed',
                error_message = #{errorMessage},
                completed_at = #{completedAt}
            WHERE status = 'running'
            """)
    int markRunningTasksFailedOnStartup(@Param("completedAt") Instant completedAt,
                                        @Param("errorMessage") String errorMessage);
}
