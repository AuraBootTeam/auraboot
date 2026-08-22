package com.auraboot.framework.party.mapper;

import com.auraboot.framework.party.entity.Party;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PartyMapper extends BaseMapper<Party> {
    @Update("""
            UPDATE ab_party
               SET lifecycle_status = #{toStatus}, updated_at = CURRENT_TIMESTAMP, updated_by = #{updatedBy}
             WHERE tenant_id = #{tenantId} AND id = #{partyId} AND deleted_flag = FALSE
            """)
    int updateLifecycle(
            @Param("tenantId") Long tenantId,
            @Param("partyId") Long partyId,
            @Param("toStatus") String toStatus,
            @Param("updatedBy") Long updatedBy);
}
