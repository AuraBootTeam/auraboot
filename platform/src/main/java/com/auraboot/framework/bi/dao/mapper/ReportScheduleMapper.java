package com.auraboot.framework.bi.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.auraboot.framework.bi.dao.entity.ReportSchedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for report schedule CRUD operations.
 */
@Mapper
public interface ReportScheduleMapper extends BaseMapper<ReportSchedule> {

    @Select("SELECT * FROM ab_report_schedule WHERE tenant_id = #{tenantId} AND deleted_flag = FALSE ORDER BY created_at DESC")
    List<ReportSchedule> findByTenantId(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM ab_report_schedule WHERE enabled = TRUE AND deleted_flag = FALSE")
    List<ReportSchedule> findAllEnabled();

    @Select("SELECT * FROM ab_report_schedule WHERE report_id = #{reportId} AND tenant_id = #{tenantId} AND deleted_flag = FALSE")
    List<ReportSchedule> findByReportId(@Param("reportId") String reportId, @Param("tenantId") Long tenantId);
}
