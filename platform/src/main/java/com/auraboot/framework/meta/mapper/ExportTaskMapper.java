package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.ExportTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for ab_export_task table.
 */
@Mapper
public interface ExportTaskMapper extends BaseMapper<ExportTask> {

    @Select("SELECT * FROM ab_export_task WHERE pid = #{pid}")
    ExportTask findByPid(@Param("pid") String pid);

    @Select("SELECT * FROM ab_export_task WHERE query_code = #{queryCode} ORDER BY created_at DESC LIMIT #{limit}")
    List<ExportTask> findByQueryCode(@Param("queryCode") String queryCode, @Param("limit") int limit);

    @Update("UPDATE ab_export_task SET status = #{status}, progress = #{progress}, processed_rows = #{processedRows} WHERE id = #{id}")
    int updateProgress(@Param("id") Long id, @Param("status") String status,
                       @Param("progress") int progress, @Param("processedRows") long processedRows);

    @Select("SELECT * FROM ab_export_task WHERE status = 'completed' AND expires_at < #{now}")
    List<ExportTask> findExpired(@Param("now") Instant now);
}
