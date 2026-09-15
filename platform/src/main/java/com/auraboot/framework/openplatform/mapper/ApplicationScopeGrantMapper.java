package com.auraboot.framework.openplatform.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Set;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ApplicationScopeGrantMapper {
    @Select("""
            SELECT scope_code FROM ab_application_scope_grant
            WHERE tenant_id = #{tenantId} AND installation_id = #{installationId}
            ORDER BY scope_code
            """)
    Set<String> findScopes(@Param("tenantId") Long tenantId,
                           @Param("installationId") Long installationId);

    @Insert("""
            INSERT INTO ab_application_scope_grant
                (tenant_id, installation_id, scope_code, granted_by_pid)
            VALUES (#{tenantId}, #{installationId}, #{scope}, #{actorPid})
            ON CONFLICT (installation_id, scope_code) DO NOTHING
            """)
    int grant(@Param("tenantId") Long tenantId, @Param("installationId") Long installationId,
              @Param("scope") String scope, @Param("actorPid") String actorPid);

    @Delete("""
            DELETE FROM ab_application_scope_grant
            WHERE tenant_id = #{tenantId} AND installation_id = #{installationId}
            """)
    int deleteForInstallation(@Param("tenantId") Long tenantId,
                              @Param("installationId") Long installationId);
}
