package com.auraboot.framework.party.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * Write-side access for the party member role binding (E3/#2555 bootstrap
 * bridge). The read side lives in {@link PartyMemberRoleMapper}.
 */
@Mapper
public interface PartyMemberRoleBindingMapper {

    @Insert("""
            INSERT INTO ab_party_member_role
                (pid, tenant_id, party_membership_id, role_id, role_scope, effective_at, created_at, created_by)
            VALUES
                (#{pid}, #{tenantId}, #{partyMembershipId}, #{roleId}, 'party', #{effectiveAt}, #{createdAt}, #{createdBy})
            """)
    int insertBinding(@Param("pid") String pid,
                      @Param("tenantId") Long tenantId,
                      @Param("partyMembershipId") Long partyMembershipId,
                      @Param("roleId") Long roleId,
                      @Param("createdBy") Long createdBy,
                      @Param("effectiveAt") Instant effectiveAt,
                      @Param("createdAt") Instant createdAt);
}
