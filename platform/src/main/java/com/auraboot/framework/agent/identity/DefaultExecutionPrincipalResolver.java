package com.auraboot.framework.agent.identity;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.service.AgentReleaseDeploymentService;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

/**
 * Default principal resolver backed by Agent, IAM and organization records.
 */
@Component
@RequiredArgsConstructor
public class DefaultExecutionPrincipalResolver implements ExecutionPrincipalResolver {

    private static final String DEFAULT_AGENT_CODE = AuraBotAgentResolver.DEFAULT_AGENT_CODE;

    private final AgentDefinitionMapper agentDefinitionMapper;
    private final TenantMemberService tenantMemberService;
    private final UserRoleService userRoleService;
    private final UserService userService;
    private final DynamicDataMapper dynamicDataMapper;
    private final AuraBotAgentResolver auraBotAgentResolver;
    private final AgentReleaseDeploymentService releaseDeploymentService;

    @Override
    public ExecutionPrincipal resolve(ResolveRequest request) {
        validateRequest(request);
        String agentCode = normalizeAgentCode(request.agentCode());
        TenantMember initiatorMember = resolveInitiatorMember(request);
        Initiator initiator = request.initiatorOverride() != null
                ? request.initiatorOverride()
                : Initiator.human(
                        request.initiatorUserId(),
                        initiatorMember.getId(),
                        request.channel());
        AgentDefinition agent =
                agentDefinitionMapper.findByTenantIdAndAgentCode(request.tenantId(), agentCode);
        if (agent == null && DEFAULT_AGENT_CODE.equals(agentCode)) {
            Long seededAgentId =
                    auraBotAgentResolver.resolve(request.tenantId(), agentCode);
            // The first tenant+code lookup can be cached as "not found" by the
            // current MyBatis SqlSession. Reload through a different statement
            // after lazy seeding so the first AuraBot request in a transaction
            // observes the newly inserted definition immediately.
            agent = agentDefinitionMapper.selectById(seededAgentId);
        }
        if (agent == null) {
            throw new ExecutionPrincipalResolutionException(
                    ExecutionPrincipalResolutionException.Reason.AGENT_MISSING,
                    "Agent definition is missing: agentCode=" + agentCode);
        }
        if (!StatusConstants.ACTIVE.equalsIgnoreCase(agent.getStatus())) {
            throw new ExecutionPrincipalResolutionException(
                    ExecutionPrincipalResolutionException.Reason.AGENT_INACTIVE,
                    "Agent definition is inactive: agentCode=" + agentCode);
        }
        AgentReleaseDeploymentService.RuntimeBinding binding;
        try {
            binding = releaseDeploymentService.requireActive(
                    request.tenantId(), agentCode);
        } catch (RuntimeException e) {
            throw new ExecutionPrincipalResolutionException(
                    "Agent has no active immutable release deployment: agentCode="
                            + agentCode,
                    e);
        }
        validateInvocation(request, initiator, initiatorMember, binding);

        if (agent.getEmployeeId() != null) {
            return resolveDigitalEmployee(
                    request, agentCode, agent, initiator, binding);
        }
        if (initiator.type() == Initiator.Type.SYSTEM && initiatorMember != null) {
            return resolveSystemDelegated(
                    request, agentCode, initiator, initiatorMember, binding);
        }
        if (initiator.type() != Initiator.Type.HUMAN) {
            throw new ExecutionPrincipalResolutionException(
                    "System/scheduled execution requires an enrolled digital employee: agentCode="
                            + agentCode);
        }
        return resolveHumanDelegated(
                request, agentCode, agent, initiator, initiatorMember, binding);
    }

    private ExecutionPrincipal resolveDigitalEmployee(
            ResolveRequest request,
            String agentCode,
            AgentDefinition agent,
            Initiator initiator,
            AgentReleaseDeploymentService.RuntimeBinding binding) {
        Long systemUserId = agent.getSystemUserId();
        if (systemUserId == null || systemUserId <= 0L) {
            throw new ExecutionPrincipalResolutionException(
                    "Digital employee has no system user: agentCode=" + agentCode);
        }
        TenantMember actorMember =
                tenantMemberService.findByTenantIdAndUserId(request.tenantId(), systemUserId);
        if (!activeMember(actorMember)) {
            throw new ExecutionPrincipalResolutionException(
                    "Digital employee has no active service member: agentCode=" + agentCode);
        }
        if (!Objects.equals(actorMember.getEmployeeId(), agent.getEmployeeId())) {
            throw new ExecutionPrincipalResolutionException(
                    "Digital employee service member is linked to a different employee: agentCode="
                            + agentCode);
        }
        User actorUser = activeDigitalEmployeeUser(systemUserId, agentCode);
        EmployeeIdentity employee = activeEmployee(
                request.tenantId(), agent.getEmployeeId(), agentCode);
        return new ExecutionPrincipal(
                request.tenantId(),
                systemUserId,
                actorMember.getId(),
                actorUser.getPid(),
                actorUser.getUserName(),
                agent.getEmployeeId(),
                employee.pid(),
                initiator,
                delegationFor(initiator),
                agentCode,
                binding.releasePid(),
                binding.deploymentPid(),
                binding.releaseHash(),
                request.channel(),
                ExecutionPrincipal.Type.DIGITAL_EMPLOYEE,
                roleIds(actorMember.getId(), request.tenantId()));
    }

    private DelegationGrant delegationFor(Initiator initiator) {
        return switch (initiator.type()) {
            case SCHEDULE -> DelegationGrant.schedule();
            case EVENT -> DelegationGrant.event();
            case AGENT_HANDOFF -> DelegationGrant.agentHandoff();
            default -> DelegationGrant.employeeAutonomous();
        };
    }

    /**
     * Invocation gate from the immutable deployment policy.
     *
     * <p>An empty policy preserves existing tenant behavior. Once an audience
     * or channel constraint is configured it is authoritative and deny-by-
     * default for callers outside that declared set.
     */
    private void validateInvocation(
            ResolveRequest request,
            Initiator initiator,
            TenantMember initiatorMember,
            AgentReleaseDeploymentService.RuntimeBinding binding) {
        Map<String, Object> policy = binding.channelPolicy();
        if (policy == null || policy.isEmpty()) {
            return;
        }
        String channel = baseChannel(request.channel());
        List<String> allowedChannels = stringList(policy.get("allowedChannels"));
        if (!allowedChannels.isEmpty()
                && allowedChannels.stream()
                .map(this::baseChannel)
                .noneMatch(channel::equals)) {
            invocationDenied("channel is not allowed by deployment policy");
        }

        String initiatorType = initiator.type().name().toLowerCase(Locale.ROOT);
        List<String> allowedTypes = normalizedStrings(
                policy.get("allowedInitiatorTypes"));
        if (!allowedTypes.isEmpty() && !allowedTypes.contains(initiatorType)) {
            invocationDenied("initiator type is not allowed by deployment policy");
        }

        if (initiator.type() != Initiator.Type.HUMAN) {
            return;
        }
        List<Long> allowedUsers = longList(policy.get("allowedUserIds"));
        List<Long> allowedMembers = longList(policy.get("allowedMemberIds"));
        List<Long> allowedRoles = longList(policy.get("allowedRoleIds"));
        boolean audienceConfigured = !allowedUsers.isEmpty()
                || !allowedMembers.isEmpty()
                || !allowedRoles.isEmpty();
        if (!audienceConfigured) {
            return;
        }
        Set<Long> initiatorRoles = Set.copyOf(
                roleIds(initiatorMember.getId(), request.tenantId()));
        boolean allowed = allowedUsers.contains(initiator.userId())
                || allowedMembers.contains(initiator.memberId())
                || allowedRoles.stream().anyMatch(initiatorRoles::contains);
        if (!allowed) {
            invocationDenied("initiator is outside the deployment audience");
        }
    }

    private void invocationDenied(String reason) {
        throw new ExecutionPrincipalResolutionException(
                ExecutionPrincipalResolutionException.Reason.INVOCATION_DENIED,
                "Agent invocation denied: " + reason);
    }

    private String baseChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return "unknown";
        }
        String normalized = channel.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(':');
        return separator > 0 ? normalized.substring(0, separator) : normalized;
    }

    private List<String> normalizedStrings(Object value) {
        return stringList(value).stream()
                .map(item -> item.toLowerCase(Locale.ROOT))
                .toList();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private List<Long> longList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> {
                    if (item instanceof Number number) {
                        return number.longValue();
                    }
                    try {
                        return Long.parseLong(String.valueOf(item));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(item -> item > 0L)
                .toList();
    }

    private ExecutionPrincipal resolveHumanDelegated(
            ResolveRequest request,
            String agentCode,
            AgentDefinition agent,
            Initiator initiator,
            TenantMember initiatorMember,
            AgentReleaseDeploymentService.RuntimeBinding binding) {
        User actorUser = activeUser(initiator.userId(), agentCode);
        return new ExecutionPrincipal(
                request.tenantId(),
                initiator.userId(),
                initiatorMember.getId(),
                actorUser.getPid(),
                actorUser.getUserName(),
                initiatorMember.getEmployeeId(),
                null,
                initiator,
                DelegationGrant.directUser(),
                agentCode,
                binding.releasePid(),
                binding.deploymentPid(),
                binding.releaseHash(),
                request.channel(),
                ExecutionPrincipal.Type.HUMAN_DELEGATED,
                roleIds(initiatorMember.getId(), request.tenantId()));
    }

    /**
     * An explicitly identified service account may invoke an unenrolled assistant without being
     * misrepresented as a human. The account remains disabled for interactive authentication;
     * runtime authority comes from its active tenant membership and roles.
     */
    private ExecutionPrincipal resolveSystemDelegated(
            ResolveRequest request,
            String agentCode,
            Initiator initiator,
            TenantMember initiatorMember,
            AgentReleaseDeploymentService.RuntimeBinding binding) {
        User actorUser = activeServiceAccountUser(initiator.userId(), agentCode);
        return new ExecutionPrincipal(
                request.tenantId(),
                initiator.userId(),
                initiatorMember.getId(),
                actorUser.getPid(),
                actorUser.getUserName(),
                initiatorMember.getEmployeeId(),
                null,
                initiator,
                DelegationGrant.directUser(),
                agentCode,
                binding.releasePid(),
                binding.deploymentPid(),
                binding.releaseHash(),
                request.channel(),
                ExecutionPrincipal.Type.SYSTEM,
                roleIds(initiatorMember.getId(), request.tenantId()));
    }

    private TenantMember resolveInitiatorMember(ResolveRequest request) {
        Initiator override = request.initiatorOverride();
        if (override != null && override.type() != Initiator.Type.HUMAN) {
            if (override.type() != Initiator.Type.SYSTEM
                    || override.userId() == null
                    || override.userId() <= 0L
                    || override.memberId() == null
                    || override.memberId() <= 0L) {
                return null;
            }
        }
        Long userId = override != null
                ? override.userId()
                : request.initiatorUserId();
        Long expectedMemberId = override != null
                ? override.memberId()
                : request.initiatorMemberId();
        TenantMember resolved = tenantMemberService.findByTenantIdAndUserId(
                request.tenantId(), userId);
        if (!activeMember(resolved)) {
            throw new ExecutionPrincipalResolutionException(
                    "Initiator has no active tenant member");
        }
        if (expectedMemberId != null
                && !Objects.equals(expectedMemberId, resolved.getId())) {
            throw new ExecutionPrincipalResolutionException(
                    "Initiator member does not match authenticated user");
        }
        return resolved;
    }

    private User activeUser(Long userId, String agentCode) {
        User user = userService.findByUserId(userId);
        if (user == null
                || !user.isEnabled()
                || Boolean.TRUE.equals(user.getDeletedFlag())
                || "deactivated".equalsIgnoreCase(user.getDeactivationStatus())) {
            throw new ExecutionPrincipalResolutionException(
                    "Execution actor user is not active: agentCode=" + agentCode);
        }
        return user;
    }

    private User activeServiceAccountUser(Long userId, String agentCode) {
        User user = userService.findByUserId(userId);
        if (user == null
                || !"service_account".equalsIgnoreCase(user.getUserType())
                || Boolean.TRUE.equals(user.getDeletedFlag())
                || "deactivated".equalsIgnoreCase(user.getDeactivationStatus())) {
            throw new ExecutionPrincipalResolutionException(
                    "System execution actor is not an active service account: agentCode="
                            + agentCode);
        }
        return user;
    }

    /**
     * A digital employee's backing user is intentionally disabled for
     * interactive authentication. Treating {@code is_enabled=false} as an
     * execution revocation made every correctly provisioned system agent
     * impossible to run. Runtime eligibility instead comes from the active
     * Agent deployment, service membership, and org-employee binding checked
     * by this resolver; the backing row must remain a non-deleted,
     * non-deactivated {@code system_agent}.
     */
    private User activeDigitalEmployeeUser(Long userId, String agentCode) {
        User user = userService.findByUserId(userId);
        if (user == null
                || !"system_agent".equalsIgnoreCase(user.getUserType())
                || Boolean.TRUE.equals(user.getDeletedFlag())
                || "deactivated".equalsIgnoreCase(user.getDeactivationStatus())) {
            throw new ExecutionPrincipalResolutionException(
                    "Digital employee actor user is not active: agentCode=" + agentCode);
        }
        return user;
    }

    private EmployeeIdentity activeEmployee(
            long tenantId, Long employeeId, String agentCode) {
        String sql = """
                SELECT pid, org_emp_status, deleted_flag
                FROM mt_org_employee
                WHERE tenant_id = #{params.tenantId}
                  AND id = #{params.employeeId}
                LIMIT 1
                """;
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                sql, Map.of("tenantId", tenantId, "employeeId", employeeId));
        if (rows == null || rows.isEmpty()) {
            throw new ExecutionPrincipalResolutionException(
                    "Digital employee organization record is missing: agentCode=" + agentCode);
        }
        Map<String, Object> row = rows.get(0);
        if (!StatusConstants.ACTIVE.equalsIgnoreCase(
                Objects.toString(row.get("org_emp_status"), ""))
                || Boolean.TRUE.equals(row.get("deleted_flag"))) {
            throw new ExecutionPrincipalResolutionException(
                    "Digital employee organization record is inactive: agentCode=" + agentCode);
        }
        String pid = Objects.toString(row.get("pid"), "");
        if (pid.isBlank()) {
            throw new ExecutionPrincipalResolutionException(
                    "Digital employee organization identity has no pid: agentCode=" + agentCode);
        }
        return new EmployeeIdentity(pid);
    }

    private Set<Long> roleIds(Long memberId, Long tenantId) {
        List<Long> roles =
                userRoleService.getRoleIdsByMemberIdAndTenantId(memberId, tenantId);
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long roleId : roles) {
            if (roleId != null && roleId > 0L) {
                normalized.add(roleId);
            }
        }
        return Set.copyOf(normalized);
    }

    private boolean activeMember(TenantMember member) {
        return member != null
                && StatusConstants.ACTIVE.equalsIgnoreCase(member.getStatus())
                && !Boolean.TRUE.equals(member.getDeletedFlag())
                && member.getId() != null
                && member.getId() > 0L;
    }

    private void validateRequest(ResolveRequest request) {
        if (request == null) {
            throw new ExecutionPrincipalResolutionException("Principal resolve request is required");
        }
        if (request.tenantId() <= 0L) {
            throw new ExecutionPrincipalResolutionException("tenantId must be positive");
        }
        if (request.initiatorOverride() == null
                && (request.initiatorUserId() == null
                    || request.initiatorUserId() <= 0L)) {
            throw new ExecutionPrincipalResolutionException(
                    "Authenticated initiator user is required; userId=0 is not an actor");
        }
    }

    private String normalizeAgentCode(String agentCode) {
        return agentCode == null || agentCode.isBlank()
                ? DEFAULT_AGENT_CODE
                : agentCode.trim();
    }

    private record EmployeeIdentity(String pid) {
    }
}
