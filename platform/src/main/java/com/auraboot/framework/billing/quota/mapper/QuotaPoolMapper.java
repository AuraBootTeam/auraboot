package com.auraboot.framework.billing.quota.mapper;

import com.auraboot.framework.billing.quota.model.QuotaPool;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for {@link QuotaPool}.
 *
 * <p>{@code @InterceptorIgnore(tenantLine = "true")}: quota pool is a
 * platform-global table (not per-tenant) — bypasses the multi-tenant interceptor.
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface QuotaPoolMapper extends BaseMapper<QuotaPool> {
}
