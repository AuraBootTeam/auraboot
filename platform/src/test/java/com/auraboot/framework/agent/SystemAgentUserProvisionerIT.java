package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.SystemAgentUserProvisioner;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-database tests for {@link SystemAgentUserProvisioner}.
 *
 * <p>Deliberately not mocked: the whole point of this component is that a row lands in
 * {@code ab_user} with the right flags. A mocked mapper would assert that we called a method,
 * which is the one thing that was never in doubt.
 */
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("SystemAgentUserProvisioner - Integration Tests")
@Transactional
@Rollback(true)
class SystemAgentUserProvisionerIT extends BaseIntegrationTest {

    @Autowired
    private SystemAgentUserProvisioner provisioner;

    @Autowired
    private UserMapper userMapper;

    private User loadByEmail(String email) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email).last("LIMIT 1"));
    }

    @Test
    @DisplayName("creates a backing user that exists, cannot sign in, and is typed system_agent")
    void createsDisabledSystemAgentUser() {
        String agentCode = "it_provisioner_" + System.nanoTime();

        Long userId = provisioner.ensureSystemAgentUser(agentCode, "Provisioner Test Agent");

        assertThat(userId).isNotNull();
        User created = loadByEmail(SystemAgentUserProvisioner.emailFor(agentCode));
        assertThat(created).as("the user must actually be in ab_user").isNotNull();
        assertThat(created.getId()).isEqualTo(userId);
        assertThat(created.getUserType()).isEqualTo("system_agent");
        assertThat(created.isEnabled())
                .as("an agent account must never be usable as a login")
                .isFalse();
        assertThat(created.getNickName()).isEqualTo("Agent: Provisioner Test Agent");
        assertThat(created.getPid()).isNotBlank();
    }

    @Test
    @DisplayName("is find-or-create: enrolling twice does not produce a second user for one agent")
    void isIdempotentForTheSameAgentCode() {
        String agentCode = "it_provisioner_idem_" + System.nanoTime();

        Long first = provisioner.ensureSystemAgentUser(agentCode, "Idempotent Agent");
        Long second = provisioner.ensureSystemAgentUser(agentCode, "Idempotent Agent");

        assertThat(second).isEqualTo(first);
        Long rows = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, SystemAgentUserProvisioner.emailFor(agentCode)));
        assertThat(rows).as("one agent, one backing user").isEqualTo(1L);
    }

    @Test
    @DisplayName("keeps distinct agents on distinct accounts")
    void distinctAgentsGetDistinctUsers() {
        long nonce = System.nanoTime();
        Long a = provisioner.ensureSystemAgentUser("it_prov_a_" + nonce, "Agent A");
        Long b = provisioner.ensureSystemAgentUser("it_prov_b_" + nonce, "Agent B");

        assertThat(a).isNotEqualTo(b);
    }
}
