package com.auraboot.framework.cloudconfig.mapper;

import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for CloudConfig entity.
 * <p>
 * IMPORTANT: The ab_cloud_config table has PLATFORM-level rows where tenant_id IS NULL.
 * The TenantLineInterceptor would break cross-level queries, so methods that need to
 * query across both PLATFORM and TENANT levels use {@code @InterceptorIgnore(tenantLine = "true")}
 * and handle tenant filtering manually in SQL.
 *
 * @since 6.3.0
 */
@Mapper
public interface CloudConfigMapper extends BaseMapper<CloudConfig> {

    /**
     * Get the effective config for a given service type and provider code.
     * Tenant-level config takes priority over platform-level config.
     * <p>
     * Uses @InterceptorIgnore because this query must access PLATFORM rows (tenant_id IS NULL)
     * alongside TENANT rows.
     */
    @Select("""
        SELECT * FROM ab_cloud_config
        WHERE service_type = #{serviceType}
          AND provider_code = #{providerCode}
          AND (
              (config_level = 'tenant' AND tenant_id = #{tenantId})
              OR
              (config_level = 'platform' AND tenant_id IS NULL)
          )
          AND enabled = TRUE
          AND deleted_flag = FALSE
        ORDER BY CASE WHEN config_level = 'tenant' THEN 0 ELSE 1 END, priority ASC
        LIMIT 1
        """)
    @InterceptorIgnore(tenantLine = "true")
    CloudConfig getEffectiveConfig(@Param("tenantId") Long tenantId,
                                   @Param("serviceType") String serviceType,
                                   @Param("providerCode") String providerCode);

    /**
     * Get all enabled providers for a service type, considering both tenant and platform levels.
     * Tenant-level configs appear first.
     */
    @Select("""
        SELECT * FROM ab_cloud_config
        WHERE service_type = #{serviceType}
          AND (
              (config_level = 'tenant' AND tenant_id = #{tenantId})
              OR
              (config_level = 'platform' AND tenant_id IS NULL)
          )
          AND enabled = TRUE
          AND deleted_flag = FALSE
        ORDER BY CASE WHEN config_level = 'tenant' THEN 0 ELSE 1 END, priority ASC
        """)
    @InterceptorIgnore(tenantLine = "true")
    List<CloudConfig> getEnabledProviders(@Param("tenantId") Long tenantId,
                                          @Param("serviceType") String serviceType);

    /**
     * Find a config by its PID. Ignores tenant interceptor to allow PLATFORM-level lookups.
     */
    @Select("""
        SELECT * FROM ab_cloud_config
        WHERE pid = #{pid}
          AND deleted_flag = FALSE
        """)
    @InterceptorIgnore(tenantLine = "true")
    CloudConfig findByPid(@Param("pid") String pid);

    /**
     * List all configs at a given level.
     * For PLATFORM level, lists all platform configs.
     * For TENANT level, lists configs for the specified tenant.
     */
    /**
     * Get all enabled configs for a service type across all tenants and platform level.
     * Used for provider discovery (e.g., listing all known LLM providers).
     */
    @Select("""
        SELECT * FROM ab_cloud_config
        WHERE service_type = #{serviceType}
          AND deleted_flag = FALSE
        ORDER BY priority ASC
        """)
    @InterceptorIgnore(tenantLine = "true")
    List<CloudConfig> getAllByServiceType(@Param("serviceType") String serviceType);

    @Select("""
        <script>
        SELECT * FROM ab_cloud_config
        WHERE config_level = #{configLevel}
          AND deleted_flag = FALSE
          <if test="configLevel == 'tenant'">
            AND tenant_id = #{tenantId}
          </if>
          <if test="configLevel == 'platform'">
            AND tenant_id IS NULL
          </if>
        ORDER BY service_type, priority ASC
        </script>
        """)
    @InterceptorIgnore(tenantLine = "true")
    List<CloudConfig> listByLevel(@Param("configLevel") String configLevel,
                                  @Param("tenantId") Long tenantId);
}
