package com.auraboot.module.meta.excel.mapper;

import com.auraboot.module.meta.excel.entity.ImportJob;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis Plus mapper for ab_import_job table.
 *
 * @author AuraBoot Team
 * @since 6.0.0
 */
@Mapper
public interface ImportJobMapper extends BaseMapper<ImportJob> {

    /** Cross-tenant scheduler query; every returned row carries its tenant and creator scope. */
    @InterceptorIgnore(tenantLine = "true")
    @ResultMap("mybatis-plus_ImportJob")
    @Select("""
            SELECT * FROM ab_import_job
            WHERE error_report_url IS NOT NULL
              AND completed_at < #{cutoff}
              AND deleted_flag = FALSE
            ORDER BY completed_at ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<ImportJob> findExpiredReports(@Param("cutoff") LocalDateTime cutoff,
                                       @Param("limit") int limit);

    /** Preserve import history while retiring the expired correction-workbook pointer. */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE ab_import_job
            SET error_report_url = NULL, updated_at = #{updatedAt}
            WHERE id = #{id}
              AND error_report_url IS NOT NULL
              AND deleted_flag = FALSE
            """)
    int clearErrorReport(@Param("id") Long id,
                         @Param("updatedAt") LocalDateTime updatedAt);
}
