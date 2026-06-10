package com.auraboot.framework.billing.metering.mapper;

import com.auraboot.framework.billing.metering.model.UsageDedupeConflict;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for {@link UsageDedupeConflict}.
 *
 * <p>Standard CRUD is provided by {@link BaseMapper}.  This table is append-only;
 * only inserts and selects are performed by the service layer.
 *
 * <p>{@code @InterceptorIgnore(tenantLine = "true")}: the conflict log is a
 * platform-global billing table (not per-tenant), so the MyBatis-Plus
 * multi-tenant interceptor must be bypassed for all queries.
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface UsageDedupeConflictMapper extends BaseMapper<UsageDedupeConflict> {
    // No custom SQL — use QueryWrapper / LambdaQueryWrapper in service layer.
}
