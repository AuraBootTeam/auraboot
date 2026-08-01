package com.auraboot.framework.agent.identity;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.service.AgentReleaseDeploymentService;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionPrincipalResolver")
class ExecutionPrincipalResolverTest {

    private static final long TENANT_ID = 7L;
    private static final long INITIATOR_USER_ID = 101L;
    private static final long INITIATOR_MEMBER_ID = 201L;
    private static final long AGENT_USER_ID = 301L;
    private static final long AGENT_MEMBER_ID = 401L;
    private static final long EMPLOYEE_ID = 501L;

    @Mock private AgentDefinitionMapper agentDefinitionMapper;
    @Mock private TenantMemberService tenantMemberService;
    @Mock private UserRoleService userRoleService;
    @Mock private UserService userService;
    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private AuraBotAgentResolver auraBotAgentResolver;
    @Mock private AgentReleaseDeploymentService releaseDeploymentService;

    private ExecutionPrincipalResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DefaultExecutionPrincipalResolver(
                agentDefinitionMapper,
                tenantMemberService,
                userRoleService,
                userService,
                dynamicDataMapper,
                auraBotAgentResolver,
                releaseDeploymentService);
        when(releaseDeploymentService.requireActive(anyLong(), anyString()))
                .thenReturn(new AgentReleaseDeploymentService.RuntimeBinding(
                        "DEPLOYMENT_1",
                        "AGENT_RELEASE_1",
                        "release-hash-1",
                        1,
                        Map.of(),
                        Map.of(),
                        List.of(),
                        Map.of(),
                        Map.of()));
    }

    @Test
    @DisplayName("enrolled digital employee executes as its own active service account")
    void enrolledEmployeeUsesIndependentActor() {
        AgentDefinition agent = agent("sales_colleague", EMPLOYEE_ID, AGENT_USER_ID);
        when(agentDefinitionMapper.findByTenantIdAndAgentCode(TENANT_ID, "sales_colleague"))
                .thenReturn(agent);
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, AGENT_USER_ID))
                .thenReturn(member(AGENT_MEMBER_ID, AGENT_USER_ID, EMPLOYEE_ID, "active"));
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, INITIATOR_USER_ID))
                .thenReturn(member(INITIATOR_MEMBER_ID, INITIATOR_USER_ID, 601L, "active"));
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(AGENT_MEMBER_ID, TENANT_ID))
                .thenReturn(List.of(11L, 12L));
        when(userService.findByUserId(AGENT_USER_ID))
                .thenReturn(systemAgentUser(AGENT_USER_ID, "USR_AGENT", "agent-sales"));
        when(dynamicDataMapper.selectByQuery(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(List.of(Map.of(
                        "pid", "EMP_SALES",
                        "org_emp_status", "active",
                        "deleted_flag", false)));

        ExecutionPrincipal principal = resolver.resolve(request("sales_colleague", "im_group"));

        assertThat(principal.type()).isEqualTo(ExecutionPrincipal.Type.DIGITAL_EMPLOYEE);
        assertThat(principal.actorUserId()).isEqualTo(AGENT_USER_ID);
        assertThat(principal.actorMemberId()).isEqualTo(AGENT_MEMBER_ID);
        assertThat(principal.actorEmployeePid()).isEqualTo("EMP_SALES");
        assertThat(principal.roleIds()).containsExactlyInAnyOrder(11L, 12L);
        assertThat(principal.initiator().userId()).isEqualTo(INITIATOR_USER_ID);
        assertThat(principal.initiator().memberId()).isEqualTo(INITIATOR_MEMBER_ID);
        assertThat(principal.delegation().mode())
                .isEqualTo(DelegationGrant.Mode.EMPLOYEE_AUTONOMOUS);
        assertThat(principal.agentReleasePid()).isEqualTo("AGENT_RELEASE_1");
        assertThat(principal.deploymentPid()).isEqualTo("DEPLOYMENT_1");
    }

    @Test
    @DisplayName("unenrolled assistant keeps the human as actor and records direct delegation")
    void unenrolledAssistantUsesHumanDelegation() {
        when(agentDefinitionMapper.findByTenantIdAndAgentCode(TENANT_ID, "draft_agent"))
                .thenReturn(agent("draft_agent", null, null));
        TenantMember initiator =
                member(INITIATOR_MEMBER_ID, INITIATOR_USER_ID, 601L, "active");
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, INITIATOR_USER_ID))
                .thenReturn(initiator);
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(INITIATOR_MEMBER_ID, TENANT_ID))
                .thenReturn(List.of(21L));
        when(userService.findByUserId(INITIATOR_USER_ID))
                .thenReturn(user(INITIATOR_USER_ID, "USR_HUMAN", "human"));

        ExecutionPrincipal principal = resolver.resolve(request("draft_agent", "web"));

        assertThat(principal.type()).isEqualTo(ExecutionPrincipal.Type.HUMAN_DELEGATED);
        assertThat(principal.actorUserId()).isEqualTo(INITIATOR_USER_ID);
        assertThat(principal.actorMemberId()).isEqualTo(INITIATOR_MEMBER_ID);
        assertThat(principal.delegation().mode()).isEqualTo(DelegationGrant.Mode.DIRECT_USER);
    }

    @Test
    @DisplayName("enrolled definition with a missing service member fails closed")
    void brokenEmployeeIdentityFailsClosed() {
        when(agentDefinitionMapper.findByTenantIdAndAgentCode(TENANT_ID, "broken_agent"))
                .thenReturn(agent("broken_agent", EMPLOYEE_ID, AGENT_USER_ID));
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, INITIATOR_USER_ID))
                .thenReturn(member(INITIATOR_MEMBER_ID, INITIATOR_USER_ID, 601L, "active"));

        assertThatThrownBy(() -> resolver.resolve(request("broken_agent", "im_group")))
                .isInstanceOf(ExecutionPrincipalResolutionException.class)
                .hasMessageContaining("active service member");
    }

    @Test
    @DisplayName("employee member linked to another employee cannot borrow that identity")
    void employeeMemberMismatchFailsClosed() {
        when(agentDefinitionMapper.findByTenantIdAndAgentCode(TENANT_ID, "mismatch_agent"))
                .thenReturn(agent("mismatch_agent", EMPLOYEE_ID, AGENT_USER_ID));
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, AGENT_USER_ID))
                .thenReturn(member(AGENT_MEMBER_ID, AGENT_USER_ID, 999L, "active"));
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, INITIATOR_USER_ID))
                .thenReturn(member(INITIATOR_MEMBER_ID, INITIATOR_USER_ID, 601L, "active"));

        assertThatThrownBy(() -> resolver.resolve(request("mismatch_agent", "web")))
                .isInstanceOf(ExecutionPrincipalResolutionException.class)
                .hasMessageContaining("different employee");
    }

    @Test
    @DisplayName("schedule without a human runs only as an enrolled employee")
    void scheduleUsesEmployeeWithoutInventingHuman() {
        AgentDefinition agent = agent("scheduled_agent", EMPLOYEE_ID, AGENT_USER_ID);
        when(agentDefinitionMapper.findByTenantIdAndAgentCode(TENANT_ID, "scheduled_agent"))
                .thenReturn(agent);
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, AGENT_USER_ID))
                .thenReturn(member(AGENT_MEMBER_ID, AGENT_USER_ID, EMPLOYEE_ID, "active"));
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(AGENT_MEMBER_ID, TENANT_ID))
                .thenReturn(List.of(11L));
        when(userService.findByUserId(AGENT_USER_ID))
                .thenReturn(systemAgentUser(AGENT_USER_ID, "USR_AGENT", "agent-scheduled"));
        when(dynamicDataMapper.selectByQuery(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(List.of(Map.of(
                        "pid", "EMP_SCHEDULED",
                        "org_emp_status", "active",
                        "deleted_flag", false)));
        Initiator schedule =
                new Initiator(Initiator.Type.SCHEDULE, null, null, "schedule:SCH_1");

        ExecutionPrincipal principal = resolver.resolve(
                new ExecutionPrincipalResolver.ResolveRequest(
                        TENANT_ID,
                        null,
                        null,
                        "scheduled_agent",
                        "schedule:SCH_1",
                        schedule));

        assertThat(principal.type()).isEqualTo(ExecutionPrincipal.Type.DIGITAL_EMPLOYEE);
        assertThat(principal.initiator()).isEqualTo(schedule);
        assertThat(principal.actorUserId()).isEqualTo(AGENT_USER_ID);
        assertThat(principal.delegation().mode()).isEqualTo(DelegationGrant.Mode.SCHEDULE);
    }

    @Test
    @DisplayName("schedule cannot borrow authority for an unenrolled draft agent")
    void scheduleRejectsUnenrolledAgent() {
        when(agentDefinitionMapper.findByTenantIdAndAgentCode(TENANT_ID, "draft_agent"))
                .thenReturn(agent("draft_agent", null, null));

        assertThatThrownBy(() -> resolver.resolve(
                new ExecutionPrincipalResolver.ResolveRequest(
                        TENANT_ID,
                        null,
                        null,
                        "draft_agent",
                        "schedule:SCH_1",
                        new Initiator(
                                Initiator.Type.SCHEDULE,
                                null,
                                null,
                                "schedule:SCH_1"))))
                .isInstanceOf(ExecutionPrincipalResolutionException.class)
                .hasMessageContaining("requires an enrolled digital employee");
    }

    @Test
    @DisplayName("identified service account invokes an unenrolled assistant as a system principal")
    void serviceAccountUsesSystemPrincipal() {
        when(agentDefinitionMapper.findByTenantIdAndAgentCode(TENANT_ID, "public_assistant"))
                .thenReturn(agent("public_assistant", null, null));
        TenantMember serviceMember =
                member(AGENT_MEMBER_ID, AGENT_USER_ID, null, "active");
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, AGENT_USER_ID))
                .thenReturn(serviceMember);
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(AGENT_MEMBER_ID, TENANT_ID))
                .thenReturn(List.of());
        when(userService.findByUserId(AGENT_USER_ID))
                .thenReturn(serviceAccountUser(AGENT_USER_ID, "USR_SERVICE", "public-visitor"));
        Initiator system = new Initiator(
                Initiator.Type.SYSTEM,
                AGENT_USER_ID,
                AGENT_MEMBER_ID,
                "cs_widget");

        ExecutionPrincipal principal = resolver.resolve(
                new ExecutionPrincipalResolver.ResolveRequest(
                        TENANT_ID,
                        AGENT_USER_ID,
                        AGENT_MEMBER_ID,
                        "public_assistant",
                        "cs_widget",
                        system));

        assertThat(principal.type()).isEqualTo(ExecutionPrincipal.Type.SYSTEM);
        assertThat(principal.actorUserId()).isEqualTo(AGENT_USER_ID);
        assertThat(principal.actorMemberId()).isEqualTo(AGENT_MEMBER_ID);
        assertThat(principal.initiator()).isEqualTo(system);
        assertThat(principal.delegation().mode()).isEqualTo(DelegationGrant.Mode.DIRECT_USER);
    }

    @Test
    @DisplayName("system principal rejects a human account mislabeled as a service actor")
    void systemPrincipalRejectsHumanAccount() {
        when(agentDefinitionMapper.findByTenantIdAndAgentCode(TENANT_ID, "public_assistant"))
                .thenReturn(agent("public_assistant", null, null));
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, AGENT_USER_ID))
                .thenReturn(member(AGENT_MEMBER_ID, AGENT_USER_ID, null, "active"));
        when(userService.findByUserId(AGENT_USER_ID))
                .thenReturn(user(AGENT_USER_ID, "USR_HUMAN", "human"));
        Initiator system = new Initiator(
                Initiator.Type.SYSTEM,
                AGENT_USER_ID,
                AGENT_MEMBER_ID,
                "cs_widget");

        assertThatThrownBy(() -> resolver.resolve(
                new ExecutionPrincipalResolver.ResolveRequest(
                        TENANT_ID,
                        AGENT_USER_ID,
                        AGENT_MEMBER_ID,
                        "public_assistant",
                        "cs_widget",
                        system)))
                .isInstanceOf(ExecutionPrincipalResolutionException.class)
                .hasMessageContaining("active service account");
    }

    @Test
    @DisplayName("deployment channel policy rejects an otherwise valid caller")
    void channelPolicyFailsClosed() {
        when(releaseDeploymentService.requireActive(TENANT_ID, "draft_agent"))
                .thenReturn(new AgentReleaseDeploymentService.RuntimeBinding(
                        "DEPLOYMENT_1",
                        "AGENT_RELEASE_1",
                        "release-hash-1",
                        1,
                        Map.of(),
                        Map.of(),
                        List.of(),
                        Map.of("allowedChannels", List.of("web")),
                        Map.of()));
        when(agentDefinitionMapper.findByTenantIdAndAgentCode(TENANT_ID, "draft_agent"))
                .thenReturn(agent("draft_agent", null, null));
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, INITIATOR_USER_ID))
                .thenReturn(member(INITIATOR_MEMBER_ID, INITIATOR_USER_ID, 601L, "active"));

        assertThatThrownBy(() -> resolver.resolve(request("draft_agent", "im_group")))
                .isInstanceOf(ExecutionPrincipalResolutionException.class)
                .extracting(error -> ((ExecutionPrincipalResolutionException) error).reason())
                .isEqualTo(ExecutionPrincipalResolutionException.Reason.INVOCATION_DENIED);
    }

    @Test
    @DisplayName("deployment audience accepts configured initiator role")
    void audiencePolicyAcceptsInitiatorRole() {
        when(releaseDeploymentService.requireActive(TENANT_ID, "draft_agent"))
                .thenReturn(new AgentReleaseDeploymentService.RuntimeBinding(
                        "DEPLOYMENT_1",
                        "AGENT_RELEASE_1",
                        "release-hash-1",
                        1,
                        Map.of(),
                        Map.of(),
                        List.of(),
                        Map.of(
                                "allowedChannels", List.of("web"),
                                "allowedInitiatorTypes", List.of("human"),
                                "allowedRoleIds", List.of(44L)),
                        Map.of()));
        when(agentDefinitionMapper.findByTenantIdAndAgentCode(TENANT_ID, "draft_agent"))
                .thenReturn(agent("draft_agent", null, null));
        TenantMember initiator =
                member(INITIATOR_MEMBER_ID, INITIATOR_USER_ID, 601L, "active");
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, INITIATOR_USER_ID))
                .thenReturn(initiator);
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(INITIATOR_MEMBER_ID, TENANT_ID))
                .thenReturn(List.of(44L));
        when(userService.findByUserId(INITIATOR_USER_ID))
                .thenReturn(user(INITIATOR_USER_ID, "USR_HUMAN", "human"));

        assertThat(resolver.resolve(request("draft_agent", "web")).actorUserId())
                .isEqualTo(INITIATOR_USER_ID);
    }

    private ExecutionPrincipalResolver.ResolveRequest request(String agentCode, String channel) {
        return new ExecutionPrincipalResolver.ResolveRequest(
                TENANT_ID,
                INITIATOR_USER_ID,
                INITIATOR_MEMBER_ID,
                agentCode,
                channel);
    }

    private static AgentDefinition agent(String code, Long employeeId, Long systemUserId) {
        AgentDefinition agent = new AgentDefinition();
        agent.setId(701L);
        agent.setPid("AGENT_RELEASE_1");
        agent.setTenantId(TENANT_ID);
        agent.setAgentCode(code);
        agent.setStatus("active");
        agent.setEmployeeId(employeeId);
        agent.setSystemUserId(systemUserId);
        return agent;
    }

    private static TenantMember member(
            Long id, Long userId, Long employeeId, String status) {
        TenantMember member = new TenantMember();
        member.setId(id);
        member.setPid("MEMBER_" + id);
        member.setTenantId(TENANT_ID);
        member.setUserId(userId);
        member.setEmployeeId(employeeId);
        member.setStatus(status);
        member.setDeletedFlag(false);
        return member;
    }

    private static User user(Long id, String pid, String username) {
        User user = new User();
        user.setId(id);
        user.setPid(pid);
        user.setUserName(username);
        user.setEnabled(true);
        user.setDeletedFlag(false);
        return user;
    }

    private static User systemAgentUser(Long id, String pid, String username) {
        User user = user(id, pid, username);
        user.setUserType("system_agent");
        user.setEnabled(false);
        return user;
    }

    private static User serviceAccountUser(Long id, String pid, String username) {
        User user = user(id, pid, username);
        user.setUserType("service_account");
        user.setEnabled(false);
        return user;
    }
}
