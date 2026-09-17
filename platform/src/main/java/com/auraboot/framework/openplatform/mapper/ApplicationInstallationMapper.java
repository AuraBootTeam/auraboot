package com.auraboot.framework.openplatform.mapper;

import com.auraboot.framework.openplatform.entity.ApplicationInstallation;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ApplicationInstallationMapper extends BaseMapper<ApplicationInstallation> {
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT i.* FROM ab_application_installation i
            JOIN ab_external_application a ON a.id = i.application_id
            WHERE i.tenant_id = #{tenantId} AND a.pid = #{applicationPid}
            ORDER BY i.installed_at DESC
            """)
    List<ApplicationInstallation> findByApplicationPid(@Param("tenantId") Long tenantId,
                                                        @Param("applicationPid") String applicationPid);

    @Select("""
            SELECT * FROM ab_application_installation
            WHERE tenant_id = #{tenantId} AND pid = #{pid}
            LIMIT 1
            """)
    ApplicationInstallation findByTenantAndPid(@Param("tenantId") Long tenantId,
                                                @Param("pid") String pid);
}
