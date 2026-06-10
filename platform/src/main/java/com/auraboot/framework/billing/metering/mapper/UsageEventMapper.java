package com.auraboot.framework.billing.metering.mapper;

import com.auraboot.framework.billing.metering.model.UsageEvent;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for {@link UsageEvent}.
 *
 * <p>Standard CRUD is provided by {@link BaseMapper}.  Service-layer queries
 * use {@code QueryWrapper<UsageEvent>} / {@code LambdaQueryWrapper}.
 *
 * <p>{@code @InterceptorIgnore(tenantLine = "true")}: usage events are a
 * platform-global billing table (not per-tenant), so the MyBatis-Plus
 * multi-tenant interceptor must be bypassed for all queries.
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface UsageEventMapper extends BaseMapper<UsageEvent> {
    // No custom SQL — use QueryWrapper / LambdaQueryWrapper in service layer.
}
