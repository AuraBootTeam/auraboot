package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Find-or-create for the disabled {@code system_agent} user that backs an agent acting inside the
 * org chart (as a tenant member, and from there as an {@code org_employee} of type {@code ai}).
 *
 * <p>Why this exists as its own component: the same user was previously only ever created by
 * {@code AgentTemplateSeeder}, which scopes its query to the system tenant. Agents created by a
 * tenant after bootstrap therefore never got one, and since enrollment refuses to proceed without
 * it, no tenant-created agent could be enrolled as a digital employee at all. Enrollment now
 * provisions on demand through this class, and the seeder keeps its bootstrap-time pass so
 * platform templates are still bound up front.
 *
 * <p>The email convention is defined here and referenced by the seeder rather than written out
 * twice: two spellings of the same convention would silently produce two users for one agent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemAgentUserProvisioner {

    /** Domain for agent-backing accounts. Not a routable domain — these users cannot sign in. */
    public static final String EMAIL_DOMAIN = "@system.auraboot.local";

    private static final String USER_TYPE_SYSTEM_AGENT = "system_agent";

    private final UserMapper userMapper;

    /** The address {@code agentCode}'s backing user occupies. */
    public static String emailFor(String agentCode) {
        return "agent-" + agentCode + EMAIL_DOMAIN;
    }

    /**
     * Returns the id of the backing user for this agent, creating it if absent.
     *
     * <p>The created user is {@code is_enabled = false} on purpose: it exists to own memberships
     * and audit trails, never to authenticate. JWT auth rejects disabled accounts, so an agent
     * account cannot be used as a login even if its address were guessed.
     */
    public Long ensureSystemAgentUser(String agentCode, String agentName) {
        String email = emailFor(agentCode);
        String displayName = "Agent: " + agentName;

        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email).last("LIMIT 1"));
        if (existing != null) {
            return existing.getId();
        }

        User user = new User();
        user.setPid(UniqueIdGenerator.generate());
        user.setEmail(email);
        user.setNickName(displayName);
        user.setUserType(USER_TYPE_SYSTEM_AGENT);
        user.setEnabled(false);
        user.setAccountNonExpired(true);
        user.setAccountNonLocked(true);
        user.setCredentialsNonExpired(true);
        userMapper.insert(user);

        log.info("Provisioned system agent user for agent '{}': userId={} email={}",
                agentCode, user.getId(), email);
        return user.getId();
    }
}
