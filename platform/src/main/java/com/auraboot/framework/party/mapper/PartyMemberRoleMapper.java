package com.auraboot.framework.party.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PartyMemberRoleMapper {
    @Select("""
            SELECT r.code
              FROM ab_party_member_role pmr
              JOIN ab_role r
                ON r.tenant_id = pmr.tenant_id
               AND r.id = pmr.role_id
               AND r.role_scope = 'party'
             WHERE pmr.tenant_id = #{tenantId}
               AND pmr.party_membership_id = #{partyMembershipId}
               AND r.status = 'active'
               AND r.deleted_flag = FALSE
               AND (pmr.expires_at IS NULL OR pmr.expires_at > CURRENT_TIMESTAMP)
             ORDER BY r.code
            """)
    List<String> findActiveRoleCodes(
            @Param("tenantId") Long tenantId,
            @Param("partyMembershipId") Long partyMembershipId);

    @Select("""
            SELECT r.id
              FROM ab_party_member_role pmr
              JOIN ab_role r
                ON r.tenant_id = pmr.tenant_id
               AND r.id = pmr.role_id
               AND r.role_scope = 'party'
             WHERE pmr.tenant_id = #{tenantId}
               AND pmr.party_membership_id = #{partyMembershipId}
               AND r.status = 'active'
               AND r.deleted_flag = FALSE
               AND (pmr.expires_at IS NULL OR pmr.expires_at > CURRENT_TIMESTAMP)
            """)
    List<Long> findActiveRoleIds(
            @Param("tenantId") Long tenantId,
            @Param("partyMembershipId") Long partyMembershipId);
}
