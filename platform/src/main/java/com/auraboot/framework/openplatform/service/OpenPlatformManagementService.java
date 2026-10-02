package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.ApplicationView;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.ApplicationMemberView;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.ApplicationPermissions;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CreateApplicationRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CredentialSecret;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CredentialView;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.InstallApplicationRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.InstallationView;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.OpenPlatformAccessView;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CallAuditView;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.OperationsOverview;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.RotateCredentialRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.RotatedCredentialSecret;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.UpdateInstallationScopesRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.UpsertApplicationMemberRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.WebhookDeliveryView;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.WebhookSubscriptionHealthView;
import com.auraboot.framework.openplatform.entity.ApplicationCredential;
import com.auraboot.framework.openplatform.entity.ApplicationInstallation;
import com.auraboot.framework.openplatform.entity.ExternalApplication;
import com.auraboot.framework.openplatform.entity.ExternalApplicationMember;
import com.auraboot.framework.openplatform.mapper.ApplicationAccessTokenMapper;
import com.auraboot.framework.openplatform.mapper.ApplicationCredentialMapper;
import com.auraboot.framework.openplatform.mapper.ApplicationInstallationMapper;
import com.auraboot.framework.openplatform.mapper.ApplicationScopeGrantMapper;
import com.auraboot.framework.openplatform.mapper.ExternalApplicationMapper;
import com.auraboot.framework.openplatform.mapper.ExternalApplicationMemberMapper;
import com.auraboot.framework.openplatform.mapper.OpenApiCallAuditMapper;
import com.auraboot.framework.webhook.mapper.WebhookDeliveryLogMapper;
import com.auraboot.framework.webhook.mapper.WebhookSubscriptionMapper;
import com.auraboot.framework.webhook.entity.WebhookSubscription;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OpenPlatformManagementService {
    private final ExternalApplicationMapper applicationMapper;
    private final ExternalApplicationMemberMapper memberMapper;
    private final ApplicationInstallationMapper installationMapper;
    private final ApplicationCredentialMapper credentialMapper;
    private final ApplicationAccessTokenMapper tokenMapper;
    private final ApplicationScopeGrantMapper scopeMapper;
    private final OpenApiCapabilityRegistry capabilityRegistry;
    private final OpenPlatformSecretCodec secretCodec;
    private final PasswordEncoder passwordEncoder;
    private final OpenApiCallAuditMapper auditMapper;
    private final WebhookDeliveryLogMapper deliveryMapper;
    private final WebhookSubscriptionMapper subscriptionMapper;
    private final OpenApiEventCatalog eventCatalog;
    private final UserPermissionService userPermissionService;
    private final UserService userService;

    public OpenPlatformAccessView getAccess() {
        boolean platformAdmin = isPlatformAdmin();
        return new OpenPlatformAccessView(platformAdmin, platformAdmin);
    }

    public List<ApplicationView> listApplications() {
        Long tenantId = requireTenant();
        String userPid = requireUserPid();
        List<ExternalApplication> applications = isPlatformAdmin()
                ? applicationMapper.findByOwnerTenant(tenantId)
                : applicationMapper.findAccessibleByUser(tenantId, userPid);
        return applications.stream().map(application -> toApplicationView(tenantId, application)).toList();
    }

    @Transactional
    public ApplicationView createApplication(CreateApplicationRequest request) {
        Long tenantId = requireTenant();
        requirePlatformAdmin();
        Instant now = Instant.now();
        ExternalApplication application = new ExternalApplication();
        application.setPid(UniqueIdGenerator.generate());
        application.setOwnerTenantId(tenantId);
        application.setName(request.name().trim());
        application.setDescription(request.description());
        application.setStatus("active");
        application.setCreatedByPid(MetaContext.getCurrentUserPid());
        application.setCreatedAt(now);
        application.setUpdatedAt(now);
        applicationMapper.insert(application);
        String creatorPid = requireUserPid();
        memberMapper.upsert(tenantId, application.getId(), creatorPid,
                OpenPlatformApplicationRole.OWNER.storageValue(), creatorPid, now);
        memberMapper.insertAudit(UniqueIdGenerator.generate(), tenantId, application.getId(), "add",
                creatorPid, null, OpenPlatformApplicationRole.OWNER.storageValue(), creatorPid, now);
        return toApplicationView(tenantId, application);
    }

    @Transactional
    public ApplicationMemberView upsertMember(String applicationPid, String userPid,
                                               UpsertApplicationMemberRequest request) {
        Long tenantId = requireTenant();
        ExternalApplication application = requireApplicationForUpdate(tenantId, applicationPid);
        requireCapability(tenantId, application, OpenPlatformApplicationRole::canManageMembers);
        UserSearchDTO user = userService.findInTenantByPid(tenantId, userPid);
        if (user == null) {
            throw new IllegalArgumentException("Active tenant member not found");
        }
        OpenPlatformApplicationRole nextRole = OpenPlatformApplicationRole.fromStorage(request.role());
        ExternalApplicationMember previous = memberMapper.findMember(tenantId, application.getId(), userPid);
        protectLastOwner(tenantId, application.getId(), previous, nextRole);
        Instant now = Instant.now();
        String actorPid = requireUserPid();
        memberMapper.upsert(tenantId, application.getId(), userPid, nextRole.storageValue(), actorPid, now);
        memberMapper.insertAudit(UniqueIdGenerator.generate(), tenantId, application.getId(),
                previous == null ? "add" : "update", userPid,
                previous == null ? null : previous.getRole(), nextRole.storageValue(), actorPid, now);
        ExternalApplicationMember saved = memberMapper.findMember(tenantId, application.getId(), userPid);
        return toMemberView(user, saved);
    }

    @Transactional
    public void removeMember(String applicationPid, String userPid) {
        Long tenantId = requireTenant();
        ExternalApplication application = requireApplicationForUpdate(tenantId, applicationPid);
        requireCapability(tenantId, application, OpenPlatformApplicationRole::canManageMembers);
        ExternalApplicationMember previous = memberMapper.findMember(tenantId, application.getId(), userPid);
        if (previous == null) {
            throw new IllegalArgumentException("Application member not found");
        }
        if (OpenPlatformApplicationRole.OWNER.storageValue().equals(previous.getRole())
                && memberMapper.countOwners(tenantId, application.getId()) <= 1) {
            throw new BusinessException(ResponseCode.BadParam,
                    "An application must keep at least one owner");
        }
        if (memberMapper.deleteMember(tenantId, application.getId(), userPid) != 1) {
            throw new IllegalStateException("Application member could not be removed");
        }
        memberMapper.insertAudit(UniqueIdGenerator.generate(), tenantId, application.getId(), "remove",
                userPid, previous.getRole(), null, requireUserPid(), Instant.now());
    }

    @Transactional
    public InstallationView install(String applicationPid, InstallApplicationRequest request) {
        Long tenantId = requireTenant();
        ExternalApplication application = requireApplication(tenantId, applicationPid);
        requireCapability(tenantId, application, OpenPlatformApplicationRole::canManageRuntime);
        Set<String> knownScopes = capabilityRegistry.list().stream()
                .map(OpenApiCapabilityRegistry.Capability::requiredScope).collect(java.util.stream.Collectors.toSet());
        if (!knownScopes.containsAll(request.scopes())) {
            throw new IllegalArgumentException("One or more scopes are not published by the Open API registry");
        }
        Instant now = Instant.now();
        ApplicationInstallation installation = new ApplicationInstallation();
        installation.setPid(UniqueIdGenerator.generate());
        installation.setTenantId(tenantId);
        installation.setApplicationId(application.getId());
        installation.setEnvironment(request.environment());
        installation.setRateLimitPerMinute(request.rateLimitPerMinute() == null ? 600 : request.rateLimitPerMinute());
        installation.setStatus("active");
        installation.setInstalledByPid(MetaContext.getCurrentUserPid());
        installation.setInstalledAt(now);
        installation.setUpdatedAt(now);
        installationMapper.insert(installation);
        request.scopes().stream().sorted().forEach(scope ->
                scopeMapper.grant(tenantId, installation.getId(), scope, MetaContext.getCurrentUserPid()));
        return toView(tenantId, installation);
    }

    @Transactional
    public CredentialSecret createCredential(String installationPid) {
        Long tenantId = requireTenant();
        ApplicationInstallation installation = requireInstallation(tenantId, installationPid);
        requireInstallationCapability(tenantId, installation, OpenPlatformApplicationRole::canManageRuntime);
        if (!"active".equals(installation.getStatus())) {
            throw new IllegalStateException("Installation is not active");
        }
        String clientSecret = secretCodec.newClientSecret();
        Instant now = Instant.now();
        ApplicationCredential credential = new ApplicationCredential();
        credential.setPid(UniqueIdGenerator.generate());
        credential.setTenantId(tenantId);
        credential.setInstallationId(installation.getId());
        credential.setClientId(secretCodec.newClientId());
        credential.setSecretHash(passwordEncoder.encode(clientSecret));
        credential.setStatus("active");
        credential.setCreatedAt(now);
        credentialMapper.insert(credential);
        return new CredentialSecret(credential.getPid(), credential.getClientId(), clientSecret, now, null);
    }

    public List<CredentialView> listCredentials(String installationPid) {
        Long tenantId = requireTenant();
        ApplicationInstallation installation = requireInstallation(tenantId, installationPid);
        requireInstallationCapability(tenantId, installation, OpenPlatformApplicationRole::canManageRuntime);
        return credentialMapper.findByInstallation(tenantId, installation.getId()).stream()
                .map(item -> new CredentialView(item.getPid(), item.getClientId(), item.getStatus(),
                        item.getCreatedAt(), item.getExpiresAt(), item.getLastUsedAt()))
                .toList();
    }

    @Transactional
    public RotatedCredentialSecret rotateCredential(String installationPid, String credentialPid,
                                                     RotateCredentialRequest request) {
        Long tenantId = requireTenant();
        ApplicationInstallation installation = requireInstallation(tenantId, installationPid);
        requireInstallationCapability(tenantId, installation, OpenPlatformApplicationRole::canManageRuntime);
        ApplicationCredential previous = credentialMapper.findByInstallation(tenantId, installation.getId()).stream()
                .filter(item -> credentialPid.equals(item.getPid()) && "active".equals(item.getStatus()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Active credential not found"));
        int graceMinutes = request.graceMinutes() == null ? 1440 : request.graceMinutes();
        Instant expiresAt = Instant.now().plus(graceMinutes, ChronoUnit.MINUTES);
        if (credentialMapper.scheduleExpiry(tenantId, previous.getPid(), expiresAt) != 1) {
            throw new IllegalStateException("Credential could not be scheduled for expiry");
        }
        CredentialSecret replacement = createCredential(installationPid);
        return new RotatedCredentialSecret(replacement, previous.getPid(), expiresAt);
    }

    public OperationsOverview getOperationsOverview(String installationPid, int windowHours) {
        Long tenantId = requireTenant();
        ApplicationInstallation installation = requireInstallation(tenantId, installationPid);
        requireInstallationCapability(tenantId, installation, OpenPlatformApplicationRole::canReadOperations);
        int boundedWindow = Math.max(1, Math.min(windowHours, 24 * 30));
        Instant since = Instant.now().minus(boundedWindow, ChronoUnit.HOURS);
        OpenApiCallAuditMapper.OperationsSummary summary = auditMapper.summarize(tenantId, installationPid, since);
        long total = summary == null ? 0 : summary.totalCalls();
        long errors = summary == null ? 0 : summary.errorCalls();
        return new OperationsOverview(boundedWindow, since, total, errors,
                summary == null ? 0 : summary.throttledCalls(), total == 0 ? 0 : (double) errors / total,
                summary == null ? 0 : summary.p95DurationMs(),
                deliveryMapper.countDeadLetters(tenantId, installationPid, since));
    }

    public List<CallAuditView> listCallAudits(String installationPid, String requestId,
                                              Integer status, int limit) {
        Long tenantId = requireTenant();
        ApplicationInstallation installation = requireInstallation(tenantId, installationPid);
        requireInstallationCapability(tenantId, installation, OpenPlatformApplicationRole::canReadOperations);
        String normalizedRequestId = requestId == null || requestId.isBlank() ? null : requestId.trim();
        return auditMapper.findForOperations(tenantId, installationPid, normalizedRequestId, status,
                        Math.max(1, Math.min(limit, 200))).stream()
                .map(item -> new CallAuditView(item.getRequestId(), item.getHttpMethod(), item.getRequestPath(),
                        item.getResponseStatus(), item.getDurationMs(), item.getOccurredAt()))
                .toList();
    }

    public List<WebhookDeliveryView> listWebhookDeliveries(String installationPid, String status, int limit) {
        Long tenantId = requireTenant();
        ApplicationInstallation installation = requireInstallation(tenantId, installationPid);
        requireInstallationCapability(tenantId, installation, OpenPlatformApplicationRole::canReadOperations);
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toLowerCase();
        return deliveryMapper.findForOperations(tenantId, installationPid, normalizedStatus,
                        Math.max(1, Math.min(limit, 200))).stream()
                .map(item -> new WebhookDeliveryView(item.pid(), item.subscriptionName(), item.eventId(),
                        item.deliveryStatus(), item.retryCount(), item.maxRetries(), item.responseStatus(),
                        safeFailureReason(item.deliveryStatus(), item.responseStatus()),
                        item.nextRetryAt(), item.lastAttemptAt(), item.deliveredAt(),
                        item.replayCount(), item.lastReplayedAt(), item.createdAt(),
                        "dead_letter".equals(item.deliveryStatus()) || "failed".equals(item.deliveryStatus())))
                .toList();
    }

    public List<WebhookSubscriptionHealthView> listWebhookHealth(String installationPid) {
        Long tenantId = requireTenant();
        ApplicationInstallation installation = requireInstallation(tenantId, installationPid);
        requireInstallationCapability(tenantId, installation, OpenPlatformApplicationRole::canReadOperations);
        Instant now = Instant.now();
        return subscriptionMapper.findByTenant(tenantId).stream()
                .filter(item -> installationPid.equals(item.getInstallationPid()))
                .map(item -> toWebhookHealth(item, now))
                .toList();
    }

    private WebhookSubscriptionHealthView toWebhookHealth(WebhookSubscription subscription, Instant now) {
        int version = subscription.getEventVersion() == null ? 1 : subscription.getEventVersion();
        OpenApiEventCatalog.EventDescriptor descriptor = eventCatalog.event(subscription.getEventType())
                .filter(OpenApiEventCatalog.EventDescriptor::externallyDeliverable).orElse(null);
        boolean compatible = descriptor != null && descriptor.supportedVersions().contains(version);
        Instant dueAt = subscription.getSecretRotatedAt() == null ? null
                : subscription.getSecretRotatedAt().plus(90, ChronoUnit.DAYS);
        String rotationStatus;
        if (subscription.getSecret() == null || subscription.getSecret().isBlank()) {
            rotationStatus = "missing";
        } else if (dueAt != null && !dueAt.isAfter(now)) {
            rotationStatus = "overdue";
        } else if (dueAt != null && !dueAt.isAfter(now.plus(14, ChronoUnit.DAYS))) {
            rotationStatus = "due";
        } else {
            rotationStatus = "healthy";
        }
        return new WebhookSubscriptionHealthView(subscription.getPid(), subscription.getName(),
                subscription.getEventType(), version, descriptor == null ? null : descriptor.currentVersion(),
                compatible, rotationStatus, subscription.getSecretRotatedAt(), dueAt,
                Boolean.TRUE.equals(subscription.getEnabled()));
    }

    private String safeFailureReason(String status, Integer responseStatus) {
        if (responseStatus != null && responseStatus >= 400) {
            return "HTTP " + responseStatus;
        }
        return "dead_letter".equals(status) || "failed".equals(status) ? "Delivery failed" : null;
    }

    @Transactional
    public void replayWebhookDelivery(String installationPid, String deliveryPid) {
        Long tenantId = requireTenant();
        ApplicationInstallation installation = requireInstallation(tenantId, installationPid);
        requireInstallationCapability(tenantId, installation, OpenPlatformApplicationRole::canManageRuntime);
        if (deliveryMapper.replayForInstallation(tenantId, installationPid, deliveryPid,
                MetaContext.getCurrentUserPid()) != 1) {
            throw new IllegalStateException("Webhook delivery is not replayable");
        }
    }

    @Transactional
    public void revokeCredential(String installationPid, String credentialPid) {
        Long tenantId = requireTenant();
        ApplicationInstallation installation = requireInstallation(tenantId, installationPid);
        requireInstallationCapability(tenantId, installation, OpenPlatformApplicationRole::canManageRuntime);
        ApplicationCredential credential = credentialMapper.findByInstallation(tenantId, installation.getId()).stream()
                .filter(item -> credentialPid.equals(item.getPid())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Credential not found"));
        Instant now = Instant.now();
        credentialMapper.revoke(tenantId, credentialPid, now);
        tokenMapper.revokeCredentialTokens(tenantId, credential.getId(), now);
    }

    @Transactional
    public void disableInstallation(String installationPid) {
        Long tenantId = requireTenant();
        ApplicationInstallation installation = requireInstallation(tenantId, installationPid);
        requireInstallationCapability(tenantId, installation, OpenPlatformApplicationRole::canManageRuntime);
        installation.setStatus("disabled");
        installation.setUpdatedAt(Instant.now());
        installationMapper.updateById(installation);
        tokenMapper.revokeInstallationTokens(tenantId, installation.getId(), Instant.now());
    }

    @Transactional
    public InstallationView updateScopes(String installationPid, UpdateInstallationScopesRequest request) {
        Long tenantId = requireTenant();
        ApplicationInstallation installation = requireInstallation(tenantId, installationPid);
        requireInstallationCapability(tenantId, installation, OpenPlatformApplicationRole::canManageRuntime);
        validateScopes(request.scopes());
        scopeMapper.deleteForInstallation(tenantId, installation.getId());
        request.scopes().stream().sorted().forEach(scope ->
                scopeMapper.grant(tenantId, installation.getId(), scope, MetaContext.getCurrentUserPid()));
        tokenMapper.revokeInstallationTokens(tenantId, installation.getId(), Instant.now());
        return toView(tenantId, installation);
    }

    @Transactional
    public void disableApplication(String applicationPid) {
        Long tenantId = requireTenant();
        ExternalApplication application = requireApplication(tenantId, applicationPid);
        requireCapability(tenantId, application, OpenPlatformApplicationRole::canDisableApplication);
        Instant now = Instant.now();
        application.setStatus("disabled");
        application.setUpdatedAt(now);
        applicationMapper.updateById(application);
        installationMapper.findByApplicationPid(tenantId, applicationPid).forEach(installation -> {
            installation.setStatus("disabled");
            installation.setUpdatedAt(now);
            installationMapper.updateById(installation);
            tokenMapper.revokeInstallationTokens(tenantId, installation.getId(), now);
        });
    }

    private void validateScopes(Set<String> scopes) {
        Set<String> knownScopes = capabilityRegistry.list().stream()
                .map(OpenApiCapabilityRegistry.Capability::requiredScope)
                .collect(java.util.stream.Collectors.toSet());
        if (!knownScopes.containsAll(scopes)) {
            throw new IllegalArgumentException("One or more scopes are not published by the Open API registry");
        }
    }

    private ExternalApplication requireApplication(Long tenantId, String pid) {
        ExternalApplication application = applicationMapper.findOwnedByPid(tenantId, pid);
        if (application == null || !"active".equals(application.getStatus())) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Application not found");
        }
        return application;
    }

    private ExternalApplication requireApplicationForUpdate(Long tenantId, String pid) {
        ExternalApplication application = applicationMapper.findOwnedByPidForUpdate(tenantId, pid);
        if (application == null || !"active".equals(application.getStatus())) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Application not found");
        }
        return application;
    }

    private ApplicationView toApplicationView(Long tenantId, ExternalApplication application) {
        OpenPlatformApplicationRole role = roleForApplication(tenantId, application);
        ApplicationPermissions permissions = new ApplicationPermissions(role.canManageMembers(),
                role.canDisableApplication(), role.canManageRuntime(), role.canReadOperations());
        List<ApplicationMemberView> members = memberMapper.findByApplication(tenantId, application.getId()).stream()
                .map(member -> toMemberView(userService.findInTenantByPid(tenantId, member.getUserPid()), member))
                .toList();
        return new ApplicationView(application.getPid(), application.getName(), application.getDescription(),
                application.getStatus(), application.getCreatedAt(), role.storageValue(), permissions, members,
                installationMapper.findByApplicationPid(tenantId, application.getPid()).stream()
                        .map(installation -> toView(tenantId, installation)).toList());
    }

    private ApplicationMemberView toMemberView(UserSearchDTO user, ExternalApplicationMember member) {
        return new ApplicationMemberView(member.getUserPid(), user == null ? member.getUserPid() : user.getDisplayName(),
                user == null ? null : user.getEmail(), member.getRole(), member.getCreatedAt());
    }

    private void protectLastOwner(Long tenantId, Long applicationId, ExternalApplicationMember previous,
                                  OpenPlatformApplicationRole nextRole) {
        if (previous != null && OpenPlatformApplicationRole.OWNER.storageValue().equals(previous.getRole())
                && nextRole != OpenPlatformApplicationRole.OWNER
                && memberMapper.countOwners(tenantId, applicationId) <= 1) {
            throw new BusinessException(ResponseCode.BadParam,
                    "An application must keep at least one owner");
        }
    }

    private void requireInstallationCapability(Long tenantId, ApplicationInstallation installation,
                                               java.util.function.Predicate<OpenPlatformApplicationRole> allowed) {
        ExternalApplication application = applicationMapper.selectById(installation.getApplicationId());
        if (application == null || !tenantId.equals(application.getOwnerTenantId())) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Application not found");
        }
        requireCapability(tenantId, application, allowed);
    }

    private void requireCapability(Long tenantId, ExternalApplication application,
                                   java.util.function.Predicate<OpenPlatformApplicationRole> allowed) {
        OpenPlatformApplicationRole role = roleForApplication(tenantId, application);
        if (!allowed.test(role)) {
            throw new AccessDeniedException("Application role does not allow this operation");
        }
    }

    private OpenPlatformApplicationRole roleForApplication(Long tenantId, ExternalApplication application) {
        if (isPlatformAdmin()) {
            return OpenPlatformApplicationRole.OWNER;
        }
        ExternalApplicationMember member = memberMapper.findMember(tenantId, application.getId(), requireUserPid());
        if (member == null) {
            throw new AccessDeniedException("Application membership is required");
        }
        return OpenPlatformApplicationRole.fromStorage(member.getRole());
    }

    private void requirePlatformAdmin() {
        if (!isPlatformAdmin()) {
            throw new AccessDeniedException("Open Platform administrator permission is required");
        }
    }

    private boolean isPlatformAdmin() {
        Long userId = MetaContext.getCurrentUserId();
        return userId != null && userPermissionService.hasPermission(userId, MetaPermission.SYS_CONNECTOR_MANAGE);
    }

    private String requireUserPid() {
        String userPid = MetaContext.getCurrentUserPid();
        if (userPid == null || userPid.isBlank()) {
            throw new IllegalStateException("User context is required");
        }
        return userPid;
    }

    private ApplicationInstallation requireInstallation(Long tenantId, String pid) {
        ApplicationInstallation installation = installationMapper.findByTenantAndPid(tenantId, pid);
        if (installation == null) {
            throw new IllegalArgumentException("Installation not found");
        }
        return installation;
    }

    private InstallationView toView(Long tenantId, ApplicationInstallation installation) {
        return new InstallationView(installation.getPid(), installation.getEnvironment(), installation.getStatus(),
                scopeMapper.findScopes(tenantId, installation.getId()), installation.getRateLimitPerMinute(),
                installation.getInstalledAt());
    }

    private Long requireTenant() {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required");
        }
        return tenantId;
    }
}
