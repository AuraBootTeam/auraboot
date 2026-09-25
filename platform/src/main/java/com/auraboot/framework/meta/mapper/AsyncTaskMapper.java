package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.AsyncTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    @Update("""
            UPDATE ab_async_task SET status='running', started_at=clock_timestamp(),
              execution_token=#{token}, lease_until=clock_timestamp() + interval '90 seconds'
            WHERE id=#{id} AND tenant_id=#{tenantId} AND status='pending'
            """)
    int claimPending(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("token") String token);

    @Update("""
            UPDATE ab_async_task SET lease_until=clock_timestamp() + interval '90 seconds'
            WHERE id=#{id} AND tenant_id=#{tenantId} AND execution_token=#{token}
              AND status='running' AND lease_until > clock_timestamp()
            """)
    int renewLease(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("token") String token);

    @Update("""
            UPDATE ab_async_task SET status='pending', started_at=NULL, execution_token=NULL, lease_until=NULL
            WHERE status='running' AND execution_token IS NOT NULL AND lease_until <= clock_timestamp()
              AND task_type='command-handler' AND input_params->>'resumeOnRestart'='true'
            """)
    int requeueExpiredResumableTasks();

    @Select("SELECT * FROM ab_async_task WHERE status='pending' AND task_type='command-handler' AND input_params->>'resumeOnRestart'='true' ORDER BY priority, created_at LIMIT #{limit}")
    List<AsyncTask> findResumablePendingTasks(@Param("limit") int limit);

    @Update("""
            UPDATE ab_async_task SET progress=#{progress}, progress_message=#{message}
            WHERE id=#{id} AND tenant_id=#{tenantId} AND execution_token=#{token}
              AND status='running' AND lease_until > clock_timestamp()
            """)
    int updateOwnedProgress(@Param("id") Long id, @Param("tenantId") Long tenantId,
                            @Param("token") String token, @Param("progress") int progress,
                            @Param("message") String message);

    @Update("""
            UPDATE ab_async_task SET status=#{task.status}, progress=#{task.progress},
              progress_message=#{task.progressMessage}, error_message=#{task.errorMessage},
              result_data=#{task.resultData,typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler,jdbcType=OTHER},
              retry_count=#{task.retryCount}, completed_at=#{task.completedAt}, cancelled_at=#{task.cancelledAt},
              execution_token=NULL, lease_until=NULL
            WHERE id=#{task.id} AND tenant_id=#{task.tenantId} AND execution_token=#{task.executionToken}
              AND status='running' AND lease_until > clock_timestamp()
            """)
    int finishOwned(@Param("task") AsyncTask task);

    @Update("""
            UPDATE ab_async_task SET status='cancelled', cancelled_at=clock_timestamp(),
              execution_token=NULL, lease_until=NULL
            WHERE id=#{id} AND tenant_id=#{tenantId} AND status IN ('pending','running')
            """)
    int cancelActive(@Param("id") Long id, @Param("tenantId") Long tenantId);

    @Update("""
            UPDATE ab_async_task SET status='failed', completed_at=clock_timestamp(),
              error_message='Execution lease expired; explicit retry required', execution_token=NULL, lease_until=NULL
            WHERE status='running' AND execution_token IS NOT NULL AND lease_until <= clock_timestamp()
              AND NOT (task_type='command-handler' AND COALESCE(input_params->>'resumeOnRestart','false')='true')
            """)
    int failExpiredTasks();
}
