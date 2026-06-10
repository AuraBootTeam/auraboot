package com.auraboot.framework.billing.catalog.mapper;

import com.auraboot.framework.billing.catalog.model.ResourceCatalog;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for {@link ResourceCatalog}.
 *
 * <p>Standard CRUD is provided by {@link BaseMapper}.  Service-layer queries
 * use {@code QueryWrapper<ResourceCatalog>} / {@code LambdaQueryWrapper}.
 *
 * <p>{@code @InterceptorIgnore(tenantLine = "true")}: the resource catalog is a
 * platform-global table (not per-tenant), so the MyBatis-Plus multi-tenant
 * interceptor must be bypassed for all queries.
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ResourceCatalogMapper extends BaseMapper<ResourceCatalog> {
    // No custom SQL — use QueryWrapper in service layer.
}
