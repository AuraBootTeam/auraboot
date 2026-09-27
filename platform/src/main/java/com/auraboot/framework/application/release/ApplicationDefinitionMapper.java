package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ApplicationDefinitionMapper {
    @Select("""
            SELECT a.id AS application_id,a.code,b.current_release_id AS release_id,r.digest AS release_digest,
                   r.source_lock_identity,b.status,b.binding_version
            FROM ab_tenant_application_binding b
            JOIN ab_application a ON a.id=b.application_id
            JOIN ab_application_release r ON r.application_id=a.id AND r.release_id=b.current_release_id
            WHERE b.tenant_id=#{tenantId} AND a.code=#{applicationCode}
            """)
    @InterceptorIgnore(tenantLine = "true")
    ReleaseRow findBoundRelease(@Param("tenantId") long tenantId,
                                @Param("applicationCode") String applicationCode);

    @Select("""
            SELECT a.id AS application_id,a.code,t.release_id,r.digest AS release_digest,
                   r.source_lock_identity,'shadow' AS status,0 AS binding_version
            FROM ab_application a
            JOIN ab_application_channel_target t ON t.application_id=a.id AND t.channel='stable'
            JOIN ab_application_release r ON r.application_id=a.id AND r.release_id=t.release_id
            JOIN ab_application_release_publication p ON p.application_id=a.id AND p.release_id=t.release_id
            WHERE a.code=#{applicationCode}
            """)
    ReleaseRow findPublishedStableRelease(@Param("applicationCode") String applicationCode);

    @Select("""
            SELECT component_key,component_version,component_digest
            FROM ab_application_release_component
            WHERE release_id=#{releaseId} AND component_type='definition'
            ORDER BY component_key
            """)
    List<ComponentRow> findDefinitionComponents(@Param("releaseId") String releaseId);

    @Select("""
            SELECT * FROM ab_plugin
            WHERE tenant_id=#{tenantId} AND plugin_id=#{pluginId} AND deleted_flag=false
            """)
    @InterceptorIgnore(tenantLine = "true")
    PluginRecord findTenantPlugin(@Param("tenantId") long tenantId, @Param("pluginId") String pluginId);

    @Select("""
            SELECT * FROM ab_plugin_resource
            WHERE tenant_id=#{tenantId} AND plugin_pid=#{pluginPid}
              AND resource_type IN ('model','field','command','permission','menu','page')
            ORDER BY resource_type,resource_code
            """)
    @ResultMap("mybatis-plus_PluginResource")
    @InterceptorIgnore(tenantLine = "true")
    List<PluginResource> findComparableResources(@Param("tenantId") long tenantId,
                                                 @Param("pluginPid") String pluginPid);

    @lombok.Data
    class ReleaseRow {
        public long applicationId;
        public String code;
        public String releaseId;
        public String releaseDigest;
        public String sourceLockIdentity;
        public String status;
        public long bindingVersion;
    }

    @lombok.Data
    class ComponentRow {
        public String componentKey;
        public String componentVersion;
        public String componentDigest;
    }
}
