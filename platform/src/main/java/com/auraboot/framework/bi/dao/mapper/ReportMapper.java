package com.auraboot.framework.bi.dao.mapper;

import com.auraboot.framework.bi.dao.entity.ReportEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for the first-class {@code ab_report} report-definition table (Phase 4 slice 1).
 *
 * <p>Plain MyBatis-Plus {@link BaseMapper}. Tenant-scoping, soft-delete filtering and
 * pid lookups are handled in {@code ReportStorageService} via {@code LambdaQueryWrapper}
 * (rather than hand-written SQL) so the jsonb {@code dsl} column always flows through the
 * entity's {@link com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}.
 */
@Mapper
public interface ReportMapper extends BaseMapper<ReportEntity> {
}
