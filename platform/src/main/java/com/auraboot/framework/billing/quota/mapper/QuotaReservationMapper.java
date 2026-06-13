package com.auraboot.framework.billing.quota.mapper;

import com.auraboot.framework.billing.quota.model.QuotaReservation;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for {@link QuotaReservation}.
 *
 * <p>{@code @InterceptorIgnore(tenantLine = "true")}: quota reservation is a
 * platform-global table (not per-tenant) — bypasses the multi-tenant interceptor.
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface QuotaReservationMapper extends BaseMapper<QuotaReservation> {
}
