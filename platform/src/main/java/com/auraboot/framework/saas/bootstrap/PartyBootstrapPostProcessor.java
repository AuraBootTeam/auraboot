package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.party.entity.Party;
import com.auraboot.framework.party.entity.PartyMembership;
import com.auraboot.framework.party.mapper.PartyMapper;
import com.auraboot.framework.party.mapper.PartyMemberRoleBindingMapper;
import com.auraboot.framework.party.mapper.PartyMembershipMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Bridges the bootstrap admin into the party/actor authorization model
 * (E3/#2555): bootstrap used to stop at the tenant membership, while the
 * plugin data-access guard evaluates field grants from the party chain
 * (party → party membership → party-scope role bindings). API-only
 * deployments then failed every business command with "Plugin source data
 * access denied" until the chain was repaired by hand.
 *
 * <p>Provisions, idempotently: an active organization party for the admin, an
 * active party membership bound to the admin's tenant member, and the
 * party-scope {@code party_admin} role bound to that membership. With the
 * chain in place the first login's space selection resolves a READY actor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartyBootstrapPostProcessor implements BootstrapPostProcessor {

    static final String PARTY_CODE = "bootstrap-admin-org";
    static final String PARTY_ROLE_CODE = "party_admin";

    private final PartyMapper partyMapper;
    private final PartyMembershipMapper partyMembershipMapper;
    private final RoleMapper roleMapper;
    private final PartyMemberRoleBindingMapper partyMemberRoleBindingMapper;
    private final TenantMemberService tenantMemberService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void process(BootstrapContext context) {
        MetaContext.setContext(context.defaultTenantId(), context.adminUserId(),
                context.adminUserPid(), adminEmail(context));
        try {
            provision(context);
        } finally {
            MetaContext.clear();
        }
    }

    private void provision(BootstrapContext context) {
        var member = tenantMemberService.findByTenantIdAndUserId(
                context.defaultTenantId(), context.adminUserId());
        if (member == null) {
            log.warn("Party bootstrap skipped: admin has no tenant member in the default tenant");
            return;
        }

        PartyMembership existing = partyMembershipMapper.selectOne(new LambdaQueryWrapper<PartyMembership>()
                .eq(PartyMembership::getTenantMemberId, member.getId())
                .last("LIMIT 1"));
        if (existing != null) {
            log.info("Party bootstrap skipped: admin party membership already exists (id={})",
                    existing.getId());
            return;
        }

        Instant now = Instant.now();
        Long adminId = context.adminUserId();

        Party party = new Party();
        party.setPid(com.auraboot.framework.common.util.UlidGenerator.generate());
        party.setTenantId(context.defaultTenantId());
        party.setCode(PARTY_CODE);
        party.setDisplayName(context.adminUserPid() + "'s organization");
        party.setPartyType("organization");
        party.setLifecycleStatus("active");
        party.setDeletedFlag(false);
        party.setCreatedAt(now);
        party.setUpdatedAt(now);
        party.setCreatedBy(adminId);
        party.setUpdatedBy(adminId);
        partyMapper.insert(party);

        PartyMembership membership = new PartyMembership();
        membership.setPid(com.auraboot.framework.common.util.UlidGenerator.generate());
        membership.setTenantId(context.defaultTenantId());
        membership.setPartyId(party.getId());
        membership.setTenantMemberId(member.getId());
        membership.setStatus("active");
        membership.setJoinedAt(now);
        membership.setCreatedAt(now);
        membership.setUpdatedAt(now);
        membership.setCreatedBy(adminId);
        membership.setUpdatedBy(adminId);
        partyMembershipMapper.insert(membership);

        Role partyRole = ensurePartyAdminRole(context.defaultTenantId(), adminId, now);

        partyMemberRoleBindingMapper.insertBinding(
                com.auraboot.framework.common.util.UlidGenerator.generate(),
                context.defaultTenantId(), membership.getId(), partyRole.getId(),
                adminId, now, now);

        log.info("Party bootstrap complete: party={}, membership={}, partyRole={}",
                party.getPid(), membership.getPid(), partyRole.getCode());
    }

    private Role ensurePartyAdminRole(Long tenantId, Long adminId, Instant now) {
        Role existing = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getTenantId, tenantId)
                .eq(Role::getRoleScope, "party")
                .eq(Role::getCode, PARTY_ROLE_CODE)
                .eq(Role::getDeletedFlag, false)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        Role role = new Role();
        role.setPid(com.auraboot.framework.common.util.UlidGenerator.generate());
        role.setTenantId(tenantId);
        role.setCode(PARTY_ROLE_CODE);
        role.setName("Party Administrator");
        role.setDescription("Party-scope administrator bound at bootstrap (E3/#2555)");
        role.setType("SYSTEM");
        role.setRoleScope("party");
        role.setStatus("ACTIVE");
        role.setIsSystem(true);
        role.setCreatedAt(now);
        role.setUpdatedAt(now);
        roleMapper.insert(role);
        return role;
    }

    private String adminEmail(BootstrapContext context) {
        // MetaContext email is display-only here; the pid is the identity that
        // the party chain binds. Keep the shape stable for downstream filters.
        return context.adminUserPid();
    }
}
