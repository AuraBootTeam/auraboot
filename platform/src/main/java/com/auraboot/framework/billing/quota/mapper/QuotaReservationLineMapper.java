package com.auraboot.framework.billing.quota.mapper;

import com.auraboot.framework.billing.quota.model.QuotaReservationLine;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for {@link QuotaReservationLine}.
 *
 * <p>{@code @InterceptorIgnore(tenantLine = "true")}: reservation lines are
 * platform-global — bypasses the multi-tenant interceptor.
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface QuotaReservationLineMapper extends BaseMapper<QuotaReservationLine> {
}
