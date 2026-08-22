package com.auraboot.framework.party.mapper;

import com.auraboot.framework.party.dto.PartyActorOption;
import com.auraboot.framework.party.entity.PartyMembership;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PartyMembershipMapper extends BaseMapper<PartyMembership> {
    @Select("""
            SELECT p.id AS party_id,
                   p.pid AS party_pid,
                   p.code AS party_code,
                   p.display_name,
                   p.party_type,
                   p.lifecycle_status,
                   pm.id AS party_membership_id,
                   pm.status AS membership_status
              FROM ab_party_membership pm
              JOIN ab_party p
                ON p.tenant_id = pm.tenant_id AND p.id = pm.party_id
             WHERE pm.tenant_id = #{tenantId}
               AND pm.tenant_member_id = #{tenantMemberId}
               AND pm.status IN ('active', 'pending', 'suspended')
               AND p.deleted_flag = FALSE
             ORDER BY p.display_name, p.id
            """)
    List<PartyActorOption> findActorOptions(
            @Param("tenantId") Long tenantId,
            @Param("tenantMemberId") Long tenantMemberId);

    @Select("""
            SELECT p.id AS party_id,
                   p.pid AS party_pid,
                   p.code AS party_code,
                   p.display_name,
                   p.party_type,
                   p.lifecycle_status,
                   pm.id AS party_membership_id,
                   pm.status AS membership_status
              FROM ab_party_membership pm
              JOIN ab_party p
                ON p.tenant_id = pm.tenant_id AND p.id = pm.party_id
             WHERE pm.tenant_id = #{tenantId}
               AND pm.tenant_member_id = #{tenantMemberId}
               AND pm.party_id = #{partyId}
               AND pm.status = 'active'
               AND p.lifecycle_status = 'active'
               AND p.deleted_flag = FALSE
             LIMIT 1
            """)
    PartyActorOption findActiveActor(
            @Param("tenantId") Long tenantId,
            @Param("tenantMemberId") Long tenantMemberId,
            @Param("partyId") Long partyId);

    @Update("""
            UPDATE ab_party_membership
               SET status = 'active', joined_at = COALESCE(joined_at, CURRENT_TIMESTAMP),
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{updatedBy}
             WHERE tenant_id = #{tenantId} AND party_id = #{partyId} AND status = 'pending'
            """)
    int activatePendingForParty(
            @Param("tenantId") Long tenantId,
            @Param("partyId") Long partyId,
            @Param("updatedBy") Long updatedBy);
}
