package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.ExportTask;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.*;

/** Trusted lifecycle registry queries are always constrained by the persisted execution owner. */
@Mapper
@InterceptorIgnore(tenantLine="true")
public interface DomainExportMapper {
    @Select("SELECT * FROM ab_export_task WHERE tenant_id=#{tenant} AND created_by=#{actor} AND pid=#{pid}")
    @ResultMap("com.auraboot.framework.meta.mapper.ExportTaskMapper.mybatis-plus_ExportTask")
    ExportTask owned(@Param("tenant") Long tenant,@Param("actor") Long actor,@Param("pid") String pid);
    @Select("SELECT * FROM ab_export_task WHERE tenant_id=#{tenant} AND created_by=#{actor} AND pid=#{pid} FOR UPDATE")
    @ResultMap("com.auraboot.framework.meta.mapper.ExportTaskMapper.mybatis-plus_ExportTask")
    ExportTask lock(@Param("tenant") Long tenant,@Param("actor") Long actor,@Param("pid") String pid);
    @Insert("""
        INSERT INTO ab_domain_export_attempt(pid,tenant_id,export_task_pid,execution_token,file_key,temp_path,expires_at,next_cleanup_at)
        VALUES(#{pid},#{tenant},#{export},#{token},#{key},#{temp},#{expires},#{expires})
        """)
    int attempt(@Param("pid") String pid,@Param("tenant") Long tenant,@Param("export") String export,@Param("token") String token,
        @Param("key") String key,@Param("temp") String temp,@Param("expires") Instant expires);
    @Update("""
        UPDATE ab_export_task SET status='running',progress=0
        WHERE tenant_id=#{tenant} AND created_by=#{actor} AND pid=#{pid} AND status IN ('pending','running') AND expires_at>clock_timestamp()
        """)
    int running(@Param("tenant") Long tenant,@Param("actor") Long actor,@Param("pid") String pid);
    @Update("""
        UPDATE ab_export_task SET status='completed',progress=100,processed_rows=total_rows,file_key=#{key},file_size=#{size},
            completed_at=clock_timestamp(),request_params=jsonb_set(request_params,'{checksumSha256}',to_jsonb(CAST(#{checksum} AS text)))
        WHERE tenant_id=#{tenant} AND created_by=#{actor} AND pid=#{pid} AND status='running' AND expires_at>clock_timestamp()
        """)
    int complete(@Param("tenant") Long tenant,@Param("actor") Long actor,@Param("pid") String pid,@Param("key") String key,
        @Param("size") long size,@Param("checksum") String checksum);
    @Select("""
        SELECT pid,tenant_id,export_task_pid,file_key,temp_path FROM ab_domain_export_attempt
        WHERE next_cleanup_at<=clock_timestamp() AND expires_at<=clock_timestamp() ORDER BY next_cleanup_at,pid LIMIT 100
        """)
    List<Map<String,Object>> dueCleanup();
    @Update("""
        UPDATE ab_domain_export_attempt SET cleanup_count=cleanup_count+1,cleanup_error=#{error},
            cleaned_at=CASE WHEN #{error} IS NULL THEN clock_timestamp() ELSE cleaned_at END,
            next_cleanup_at=clock_timestamp()+INTERVAL '1 hour'
        WHERE tenant_id=#{tenant} AND pid=#{pid}
        """)
    int cleaned(@Param("tenant") Long tenant,@Param("pid") String pid,@Param("error") String error);
}
