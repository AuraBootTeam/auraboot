package com.auraboot.framework.openplatform.mapper;

import com.auraboot.framework.openplatform.entity.ExternalApplication;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ExternalApplicationMapper extends BaseMapper<ExternalApplication> {
    @Select("""
            SELECT * FROM ab_external_application
            WHERE owner_tenant_id = #{tenantId}
            ORDER BY created_at DESC
            """)
    List<ExternalApplication> findByOwnerTenant(@Param("tenantId") Long tenantId);

    @Select("""
            SELECT a.* FROM ab_external_application a
            JOIN ab_external_application_member m
              ON m.tenant_id = a.owner_tenant_id AND m.application_id = a.id
            WHERE a.owner_tenant_id = #{tenantId} AND m.user_pid = #{userPid}
            ORDER BY a.created_at DESC
            """)
    List<ExternalApplication> findAccessibleByUser(@Param("tenantId") Long tenantId,
                                                    @Param("userPid") String userPid);

    @Select("""
            SELECT * FROM ab_external_application
            WHERE owner_tenant_id = #{tenantId} AND pid = #{pid}
            LIMIT 1
            """)
    ExternalApplication findOwnedByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);

    @Select("""
            SELECT * FROM ab_external_application
            WHERE owner_tenant_id = #{tenantId} AND pid = #{pid}
            LIMIT 1
            FOR UPDATE
            """)
    ExternalApplication findOwnedByPidForUpdate(@Param("tenantId") Long tenantId,
                                                 @Param("pid") String pid);
}
