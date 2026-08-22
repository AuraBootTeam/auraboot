package com.auraboot.framework.party.mapper;

import com.auraboot.framework.party.entity.PartyCapability;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PartyCapabilityMapper extends BaseMapper<PartyCapability> {
    @Select("""
            SELECT capability_code
              FROM ab_party_capability
             WHERE tenant_id = #{tenantId} AND party_id = #{partyId}
               AND status = 'active'
               AND (effective_at IS NULL OR effective_at <= CURRENT_TIMESTAMP)
               AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
             ORDER BY capability_code
            """)
    List<String> findActiveCodes(@Param("tenantId") Long tenantId, @Param("partyId") Long partyId);
}
