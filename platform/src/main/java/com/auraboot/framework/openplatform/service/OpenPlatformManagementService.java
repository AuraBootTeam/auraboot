package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.ApplicationView;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CreateApplicationRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CredentialSecret;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CredentialView;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.InstallApplicationRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.InstallationView;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CallAuditView;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.OperationsOverview;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.RotateCredentialRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.RotatedCredentialSecret;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.UpdateInstallationScopesRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.WebhookDeliveryView;
import com.auraboot.framework.openplatform.entity.ApplicationCredential;
import com.auraboot.framework.openplatform.entity.ApplicationInstallation;
import com.auraboot.framework.openplatform.entity.ExternalApplication;
import com.auraboot.framework.openplatform.mapper.ApplicationAccessTokenMapper;
import com.auraboot.framework.openplatform.mapper.ApplicationCredentialMapper;
import com.auraboot.framework.openplatform.mapper.ApplicationInstallationMapper;
import com.auraboot.framework.openplatform.mapper.ApplicationScopeGrantMapper;
import com.auraboot.framework.openplatform.mapper.ExternalApplicationMapper;
import com.auraboot.framework.openplatform.mapper.OpenApiCallAuditMapper;
import com.auraboot.framework.webhook.mapper.WebhookDeliveryLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final ApplicationInstallationMapper installationMapper;
    private final ApplicationCredentialMapper credentialMapper;
    private final ApplicationAccessTokenMapper tokenMapper;
    private final ApplicationScopeGrantMapper scopeMapper;
    private final OpenApiCapabilityRegistry capabilityRegistry;
    private final OpenPlatformSecretCodec secretCodec;
    private final PasswordEncoder passwordEncoder;
    private final OpenApiCallAuditMapper auditMapper;
    private final WebhookDeliveryLogMapper deliveryMapper;

    public List<ApplicationView> listApplications() {
        Long tenantId = requireTenant();
        return applicationMapper.findByOwnerTenant(tenantId).stream().map(application ->
                new ApplicationView(application.getPid(), application.getName(), application.getDescription(),
                        application.getStatus(), application.getCreatedAt(),
                        installationMapper.findByApplicationPid(tenantId, application.getPid()).stream()
                                .map(installation -> toView(tenantId, installation)).toList()))
                .toList();
    }

    @Transactional
    public ApplicationView createApplication(CreateApplicationRequest request) {
        Long tenantId = requireTenant();
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
        return new ApplicationView(application.getPid(), application.getName(), application.getDescription(),
                application.getStatus(), application.getCreatedAt(), List.of());
    }

    @Transactional
    public InstallationView install(String applicationPid, InstallApplicationRequest request) {
        Long tenantId = requireTenant();
        ExternalApplication application = requireOwnedApplication(tenantId, applicationPid);
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
        requireInstallation(tenantId, installationPid);
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
        requireInstallation(tenantId, installationPid);
        String normalizedRequestId = requestId == null || requestId.isBlank() ? null : requestId.trim();
        return auditMapper.findForOperations(tenantId, installationPid, normalizedRequestId, status,
                        Math.max(1, Math.min(limit, 200))).stream()
                .map(item -> new CallAuditView(item.getRequestId(), item.getHttpMethod(), item.getRequestPath(),
                        item.getResponseStatus(), item.getDurationMs(), item.getOccurredAt()))
                .toList();
    }

    public List<WebhookDeliveryView> listWebhookDeliveries(String installationPid, String status, int limit) {
        Long tenantId = requireTenant();
        requireInstallation(tenantId, installationPid);
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

    private String safeFailureReason(String status, Integer responseStatus) {
        if (responseStatus != null && responseStatus >= 400) {
            return "HTTP " + responseStatus;
        }
        return "dead_letter".equals(status) || "failed".equals(status) ? "Delivery failed" : null;
    }

    @Transactional
    public void replayWebhookDelivery(String installationPid, String deliveryPid) {
        Long tenantId = requireTenant();
        requireInstallation(tenantId, installationPid);
        if (deliveryMapper.replayForInstallation(tenantId, installationPid, deliveryPid,
                MetaContext.getCurrentUserPid()) != 1) {
            throw new IllegalStateException("Webhook delivery is not replayable");
        }
    }

    @Transactional
    public void revokeCredential(String installationPid, String credentialPid) {
        Long tenantId = requireTenant();
        ApplicationInstallation installation = requireInstallation(tenantId, installationPid);
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
        installation.setStatus("disabled");
        installation.setUpdatedAt(Instant.now());
        installationMapper.updateById(installation);
        tokenMapper.revokeInstallationTokens(tenantId, installation.getId(), Instant.now());
    }

    @Transactional
    public InstallationView updateScopes(String installationPid, UpdateInstallationScopesRequest request) {
        Long tenantId = requireTenant();
        ApplicationInstallation installation = requireInstallation(tenantId, installationPid);
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
        ExternalApplication application = requireOwnedApplication(tenantId, applicationPid);
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

    private ExternalApplication requireOwnedApplication(Long tenantId, String pid) {
        ExternalApplication application = applicationMapper.findOwnedByPid(tenantId, pid);
        if (application == null || !"active".equals(application.getStatus())) {
            throw new IllegalArgumentException("Application not found");
        }
        return application;
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
