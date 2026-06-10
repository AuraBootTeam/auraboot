package com.auraboot.framework.billing.quota.mapper;

import com.auraboot.framework.billing.quota.model.QuotaLedger;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for {@link QuotaLedger}.
 *
 * <p>{@code @InterceptorIgnore(tenantLine = "true")}: quota ledger is a
 * platform-global table (not per-tenant) — bypasses the multi-tenant interceptor.
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface QuotaLedgerMapper extends BaseMapper<QuotaLedger> {
}
