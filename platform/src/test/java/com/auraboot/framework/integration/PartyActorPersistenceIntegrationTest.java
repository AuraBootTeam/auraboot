package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.LoginContextRef;
import com.auraboot.framework.auth.mapper.LoginApplicationChannelMapper;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.party.dto.PartyActorOption;
import com.auraboot.framework.party.entity.ActorPreference;
import com.auraboot.framework.party.entity.Party;
import com.auraboot.framework.party.entity.PartyMembership;
import com.auraboot.framework.party.mapper.ActorPreferenceMapper;
import com.auraboot.framework.party.mapper.PartyMapper;
import com.auraboot.framework.party.mapper.PartyMemberRoleMapper;
import com.auraboot.framework.party.mapper.PartyMembershipMapper;
import com.auraboot.framework.party.service.ActorCandidateResolver;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PartyActorPersistenceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private PartyMapper partyMapper;
    @Autowired
    private PartyMembershipMapper partyMembershipMapper;
    @Autowired
    private PartyMemberRoleMapper partyMemberRoleMapper;
    @Autowired
    private ActorPreferenceMapper actorPreferenceMapper;
    @Autowired
    private LoginApplicationChannelMapper loginApplicationChannelMapper;
    @Autowired
    private ActorCandidateResolver actorCandidateResolver;
    @Autowired
    private RoleService roleService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;

    @Test
    void partyTablesRemainTenantFilteredWhileLoginRoutingAndActorCvWorkOnPostgres() {
        Party party = new Party();
        party.setPid(UniqueIdGenerator.generate());
        party.setTenantId(testTenant.getId());
        party.setCode("party_it_" + System.nanoTime());
        party.setDisplayName("Party Actor Integration Test");
        party.setPartyType("organization");
        party.setLifecycleStatus("active");
        party.setDeletedFlag(false);
        party.setCreatedBy(testUser.getId());
        party.setUpdatedBy(testUser.getId());
        assertThat(partyMapper.insert(party)).isEqualTo(1);

        PartyMembership membership = new PartyMembership();
        membership.setPid(UniqueIdGenerator.generate());
        membership.setTenantId(testTenant.getId());
        membership.setPartyId(party.getId());
        membership.setTenantMemberId(testTenantMember.getId());
        membership.setStatus("active");
        membership.setJoinedAt(Instant.now());
        membership.setCreatedBy(testUser.getId());
        membership.setUpdatedBy(testUser.getId());
        assertThat(partyMembershipMapper.insert(membership)).isEqualTo(1);

        Role partyRole = new Role();
        partyRole.setPid(UniqueIdGenerator.generate());
        partyRole.setTenantId(testTenant.getId());
        partyRole.setName("Party Operator IT");
        partyRole.setCode("party_operator_it_" + System.nanoTime());
        partyRole.setDescription("Integration-only Party-scoped role");
        partyRole.setType("custom");
        partyRole.setRoleScope("party");
        partyRole.setScopeType("tenant");
        partyRole.setStatus("active");
        partyRole.setPriority(100);
        partyRole.setIsDefault(false);
        partyRole.setIsSystem(false);
        partyRole.setDeletedFlag(false);
        partyRole.setCreatedAt(Instant.now());
        partyRole.setUpdatedAt(Instant.now());
        partyRole = roleService.createRole(partyRole);

        jdbcTemplate.update(
                """
                INSERT INTO ab_party_member_role (
                    pid, tenant_id, party_membership_id, role_id, role_scope, created_by
                ) VALUES (?, ?, ?, ?, 'party', ?)
                """,
                UniqueIdGenerator.generate(), testTenant.getId(), membership.getId(),
                partyRole.getId(), testUser.getId());

        PartyActorOption actor = partyMembershipMapper.findActiveActor(
                testTenant.getId(), testTenantMember.getId(), party.getId());
        assertThat(actor).isNotNull();
        assertThat(actor.getPartyMembershipId()).isEqualTo(membership.getId());
        assertThat(partyMemberRoleMapper.findActiveRoleIds(testTenant.getId(), membership.getId()))
                .containsExactly(partyRole.getId());

        long cv1 = actorPreferenceMapper.advanceContextVersion(
                UniqueIdGenerator.generate(), testTenant.getId(), testTenantMember.getId(), party.getId(), 1);
        long cv2 = actorPreferenceMapper.advanceContextVersion(
                UniqueIdGenerator.generate(), testTenant.getId(), testTenantMember.getId(), party.getId(), 1);
        ActorPreference preference = actorPreferenceMapper.findByMember(
                testTenant.getId(), testTenantMember.getId());
        assertThat(cv1).isEqualTo(1);
        assertThat(cv2).isEqualTo(2);
        assertThat(preference.getContextVersion()).isEqualTo(2);
        assertThat(preference.getLastPartyId()).isEqualTo(party.getId());

        LoginContextRef loginContext = loginApplicationChannelMapper.resolveLoginContext(
                "business-web", "default-business-web", testTenant.getId());
        assertThat(loginContext).isNotNull();
        assertThat(loginContext.getApplicationId()).isNotNull();
        assertThat(loginContext.getLoginChannelId()).isNotNull();

        jdbcTemplate.update(
                "UPDATE ab_login_channel SET tenant_id = ? WHERE id = ?",
                testTenant.getId(), loginContext.getLoginChannelId());
        sqlSessionTemplate.clearCache();
        assertThat(loginApplicationChannelMapper.isActiveLoginContext(
                testTenant.getId(), loginContext.getApplicationId(), loginContext.getLoginChannelId()))
                .isTrue();
        assertThat(loginApplicationChannelMapper.isActiveLoginContext(
                Long.MAX_VALUE, loginContext.getApplicationId(), loginContext.getLoginChannelId()))
                .as("a tenant-owned login channel must fail closed for another tenant")
                .isFalse();

        assertThat(actorCandidateResolver.resolveActiveCandidate(
                testTenant.getId(), testTenantMember.getId(), party.getId(),
                loginContext.getApplicationId(), loginContext.getLoginChannelId()))
                .as("an unrestricted business channel accepts any active Party capability set")
                .isNotNull();
        jdbcTemplate.update(
                """
                UPDATE ab_login_channel
                   SET settings = jsonb_build_object(
                       'allowedPartyCapabilities', jsonb_build_array('supplier'))
                 WHERE id = ?
                """,
                loginContext.getLoginChannelId());
        // Fixture writes use JdbcTemplate while the policy is read through MyBatis. Clear the
        // transaction-local first-level cache so the next mapper call observes the fixture write.
        sqlSessionTemplate.clearCache();
        assertThat(actorCandidateResolver.resolveActiveCandidate(
                testTenant.getId(), testTenantMember.getId(), party.getId(),
                loginContext.getApplicationId(), loginContext.getLoginChannelId()))
                .as("a supplier-only portal rejects a Party without active supplier capability")
                .isNull();
        jdbcTemplate.update(
                """
                INSERT INTO ab_party_capability (
                    pid, tenant_id, party_id, capability_code, status, effective_at, granted_by
                ) VALUES (?, ?, ?, 'supplier', 'active', CURRENT_TIMESTAMP, ?)
                """,
                UniqueIdGenerator.generate(), testTenant.getId(), party.getId(), testUser.getId());
        sqlSessionTemplate.clearCache();
        assertThat(actorCandidateResolver.resolveActiveCandidate(
                testTenant.getId(), testTenantMember.getId(), party.getId(),
                loginContext.getApplicationId(), loginContext.getLoginChannelId()))
                .as("the same portal accepts the Party after its supplier capability is active")
                .isNotNull();

        jdbcTemplate.update(
                "UPDATE ab_login_channel SET tenant_id = NULL WHERE id = ?",
                loginContext.getLoginChannelId());
        sqlSessionTemplate.clearCache();

        MetaContext.setContext(Long.MAX_VALUE, testUser.getId(), testUser.getPid(), testUser.getUserName());
        assertThat(partyMembershipMapper.findActiveActor(
                testTenant.getId(), testTenantMember.getId(), party.getId()))
                .as("Party tables must keep the MyBatis tenant interceptor")
                .isNull();
        assertThat(loginApplicationChannelMapper.findEnabledAuthMethods(
                "business-web", "default-business-web", null))
                .as("anonymous pre-auth routing must support a typed null tenant on PostgreSQL")
                .containsExactly("email_password");
    }
}
