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
            SELECT * FROM ab_external_application
            WHERE owner_tenant_id = #{tenantId} AND pid = #{pid}
            LIMIT 1
            """)
    ExternalApplication findOwnedByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);
}
