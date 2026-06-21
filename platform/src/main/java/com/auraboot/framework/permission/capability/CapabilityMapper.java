package com.auraboot.framework.permission.capability;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Mapper for {@link CapabilityRecord} (ab_capability). */
@Mapper
public interface CapabilityMapper extends BaseMapper<CapabilityRecord> {

    @Select("""
        SELECT * FROM ab_capability
        WHERE tenant_id = #{tenantId}
        ORDER BY group_name, order_no, code
        """)
    List<CapabilityRecord> findByTenant(@Param("tenantId") Long tenantId);

    @Select("""
        SELECT * FROM ab_capability
        WHERE tenant_id = #{tenantId} AND code = #{code}
        """)
    CapabilityRecord findByTenantAndCode(@Param("tenantId") Long tenantId, @Param("code") String code);
}
