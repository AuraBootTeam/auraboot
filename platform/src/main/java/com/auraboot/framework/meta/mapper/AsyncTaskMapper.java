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

    @Update("UPDATE ab_async_task SET progress = #{progress}, progress_message = #{progressMessage} WHERE id = #{id}")
    int updateProgress(@Param("id") Long id, @Param("progress") int progress,
                       @Param("progressMessage") String progressMessage);

    @Update("UPDATE ab_async_task SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
