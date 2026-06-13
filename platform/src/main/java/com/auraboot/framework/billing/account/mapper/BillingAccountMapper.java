package com.auraboot.framework.billing.account.mapper;

import com.auraboot.framework.billing.account.entity.BillingAccount;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for {@link BillingAccount}.
 *
 * <p>Standard CRUD is provided by {@link BaseMapper}.
 *
 * <p>{@code @InterceptorIgnore(tenantLine = "true")}: billing accounts are
 * platform-global entities (not per-tenant), so the MyBatis-Plus multi-tenant
 * interceptor must be bypassed for all queries.
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface BillingAccountMapper extends BaseMapper<BillingAccount> {
    // No custom SQL — use LambdaQueryWrapper in service layer.
}
