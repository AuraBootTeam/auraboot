package com.auraboot.framework.auth.mapper;

import com.auraboot.framework.auth.entity.TenantLoginChannel;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * Mapper for {@link TenantLoginChannel}.
 * <p>
 * Note: ab_tenant_login_channel is on the tenant interceptor ignore list
 * because channels are always queried with an explicit tenantId filter.
 *
 * @since 7.0.0
 */
public interface TenantLoginChannelMapper extends BaseMapper<TenantLoginChannel> {
}
