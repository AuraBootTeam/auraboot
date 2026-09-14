package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CreateApplicationRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.InstallApplicationRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.UpdateInstallationScopesRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.RotateCredentialRequest;
import com.auraboot.framework.openplatform.mapper.OpenPlatformAuthMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenPlatformLifecycleIntegrationTest extends BaseIntegrationTest {

    @Autowired private OpenPlatformManagementService managementService;
    @Autowired private OpenPlatformTokenService tokenService;
    @Autowired private OpenPlatformAuthMapper authMapper;
    @Autowired private OpenPlatformSecretCodec secretCodec;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void credentialScopeAndInstallationLifecycleRevokeTokensImmediately() {
        var application = managementService.createApplication(
                new CreateApplicationRequest("ERP lifecycle", "real PostgreSQL integration fixture"));
        var installation = managementService.install(application.pid(), new InstallApplicationRequest(
                "production", Set.of("openapi.profile.read", "automation.events.write"), 1200));
        var credential = managementService.createCredential(installation.pid());
        var token = tokenService.issue("client_credentials", credential.clientId(),
                credential.clientSecret(), "openapi.profile.read");

        var authenticated = authMapper.findToken(secretCodec.sha256(token.accessToken()),
                OpenPlatformTokenService.AUDIENCE, Instant.now());
        assertEquals(installation.pid(), authenticated.installationPid());
        assertEquals(1200, authenticated.rateLimitPerMinute());

        managementService.updateScopes(installation.pid(),
                new UpdateInstallationScopesRequest(Set.of("automation.events.write")));
        assertNull(authMapper.findToken(secretCodec.sha256(token.accessToken()),
                OpenPlatformTokenService.AUDIENCE, Instant.now()));
        assertThrows(OpenPlatformTokenService.InvalidScopeException.class, () -> tokenService.issue(
                "client_credentials", credential.clientId(), credential.clientSecret(),
                "openapi.profile.read"));

        var replacement = tokenService.issue("client_credentials", credential.clientId(),
                credential.clientSecret(), "automation.events.write");
        managementService.disableInstallation(installation.pid());
        assertNull(authMapper.findToken(secretCodec.sha256(replacement.accessToken()),
                OpenPlatformTokenService.AUDIENCE, Instant.now()));
        assertThrows(OpenPlatformTokenService.InvalidClientException.class, () -> tokenService.issue(
                "client_credentials", credential.clientId(), credential.clientSecret(),
                "automation.events.write"));
    }

    @Test
    void credentialAndApplicationDisableCascadeAcrossActiveTokens() {
        var application = managementService.createApplication(
                new CreateApplicationRequest("Warehouse lifecycle", null));
        var firstInstallation = managementService.install(application.pid(),
                new InstallApplicationRequest("development", Set.of("openapi.profile.read"), 600));
        var firstCredential = managementService.createCredential(firstInstallation.pid());
        var firstToken = tokenService.issue("client_credentials", firstCredential.clientId(),
                firstCredential.clientSecret(), "openapi.profile.read");
        managementService.revokeCredential(firstInstallation.pid(), firstCredential.credentialPid());
        assertNull(authMapper.findToken(secretCodec.sha256(firstToken.accessToken()),
                OpenPlatformTokenService.AUDIENCE, Instant.now()));

        var secondInstallation = managementService.install(application.pid(),
                new InstallApplicationRequest("staging", Set.of("openapi.profile.read"), 60));
        var secondCredential = managementService.createCredential(secondInstallation.pid());
        var secondToken = tokenService.issue("client_credentials", secondCredential.clientId(),
                secondCredential.clientSecret(), "openapi.profile.read");
        managementService.disableApplication(application.pid());

        assertNull(authMapper.findToken(secretCodec.sha256(secondToken.accessToken()),
                OpenPlatformTokenService.AUDIENCE, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> managementService.install(application.pid(),
                new InstallApplicationRequest("production", Set.of("openapi.profile.read"), 600)));
    }

    @Test
    void credentialRotationAndEmptyOperationsQueriesUseInstallationBoundary() {
        var application = managementService.createApplication(
                new CreateApplicationRequest("Asset bridge", null));
        var installation = managementService.install(application.pid(),
                new InstallApplicationRequest("production", Set.of("assets.read", "assets.manage"), 600));
        var oldCredential = managementService.createCredential(installation.pid());

        var rotation = managementService.rotateCredential(installation.pid(), oldCredential.credentialPid(),
                new RotateCredentialRequest(15));

        assertEquals(oldCredential.credentialPid(), rotation.replacedCredentialPid());
        assertTrue(rotation.replacedCredentialExpiresAt().isAfter(Instant.now()));
        assertEquals("assets.read", tokenService.issue("client_credentials", oldCredential.clientId(),
                oldCredential.clientSecret(), "assets.read").scope());
        assertEquals("assets.manage", tokenService.issue("client_credentials", rotation.credential().clientId(),
                rotation.credential().clientSecret(), "assets.manage").scope());

        var overview = managementService.getOperationsOverview(installation.pid(), 24);
        assertEquals(0, overview.totalCalls());
        assertEquals(0, overview.deadLetterCount());
        assertTrue(managementService.listCallAudits(installation.pid(), null, null, 50).isEmpty());
        assertTrue(managementService.listWebhookDeliveries(installation.pid(), null, 50).isEmpty());
    }

    @Test
    void populatedOperationsQueriesAggregateSafelyAndReplayOnlyWithinInstallation() {
        var application = managementService.createApplication(
                new CreateApplicationRequest("Operations fixture", null));
        var installation = managementService.install(application.pid(),
                new InstallApplicationRequest("production", Set.of("assets.read"), 600));
        Long tenantId = MetaContext.getCurrentTenantId();

        jdbcTemplate.update("""
                INSERT INTO ab_open_api_call_audit
                  (pid, tenant_id, application_pid, installation_pid, token_pid, request_id,
                   http_method, request_path, response_status, duration_ms, remote_address, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, 'GET', '/api/open/v1/resources/assets/a1', ?, ?, ?, NOW())
                """, "audit-ops-1", tenantId, application.pid(), installation.pid(), "sensitive-token-pid",
                "req-ops-1", 200, 10L, "203.0.113.7");
        jdbcTemplate.update("""
                INSERT INTO ab_open_api_call_audit
                  (pid, tenant_id, application_pid, installation_pid, request_id,
                   http_method, request_path, response_status, duration_ms, occurred_at)
                VALUES (?, ?, ?, ?, ?, 'POST', '/api/open/v1/commands/assets.assign:execute', ?, ?, NOW())
                """, "audit-ops-2", tenantId, application.pid(), installation.pid(), "req-ops-2", 500, 20L);
        jdbcTemplate.update("""
                INSERT INTO ab_open_api_call_audit
                  (pid, tenant_id, application_pid, installation_pid, request_id,
                   http_method, request_path, response_status, duration_ms, occurred_at)
                VALUES (?, ?, ?, ?, ?, 'GET', '/api/open/v1/whoami', ?, ?, NOW())
                """, "audit-ops-3", tenantId, application.pid(), installation.pid(), "req-ops-3", 429, 100L);

        jdbcTemplate.update("""
                INSERT INTO ab_webhook_subscription
                  (tenant_id, pid, name, target_url, event_type, secret, installation_pid)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, tenantId, "subscription-ops-1", "Operations webhook", "https://example.invalid/hook",
                "asset.updated", "sensitive-signing-secret", installation.pid());
        jdbcTemplate.update("""
                INSERT INTO ab_webhook_delivery_log
                  (pid, tenant_id, subscription_pid, installation_pid, event_id, request_url,
                   request_body, response_status, response_body, delivery_status, retry_count,
                   max_retries, error_message, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'dead_letter', 3, 3, ?, NOW(), NOW())
                """, "delivery-ops-1", tenantId, "subscription-ops-1", installation.pid(), "event-ops-1",
                "https://secret.example.invalid/hook", "{\"secret\":\"must-not-leak\"}", 503,
                "private response body", "upstream https://secret.example.invalid failed");

        var overview = managementService.getOperationsOverview(installation.pid(), 24);
        assertEquals(3, overview.totalCalls());
        assertEquals(2, overview.errorCalls());
        assertEquals(1, overview.throttledCalls());
        assertEquals(1, overview.deadLetterCount());
        assertTrue(overview.p95DurationMs() >= 90);

        var audits = managementService.listCallAudits(installation.pid(), "req-ops-1", null, 50);
        assertEquals(1, audits.size());
        assertEquals("req-ops-1", audits.getFirst().requestId());
        var deliveries = managementService.listWebhookDeliveries(installation.pid(), "dead_letter", 50);
        assertEquals(1, deliveries.size());
        assertEquals("HTTP 503", deliveries.getFirst().failureReason());
        assertFalse(deliveries.getFirst().toString().contains("must-not-leak"));
        assertFalse(deliveries.getFirst().toString().contains("secret.example.invalid"));

        var otherInstallation = managementService.install(application.pid(),
                new InstallApplicationRequest("staging", Set.of("assets.read"), 600));
        assertThrows(IllegalStateException.class,
                () -> managementService.replayWebhookDelivery(otherInstallation.pid(), "delivery-ops-1"));
        managementService.replayWebhookDelivery(installation.pid(), "delivery-ops-1");
        assertTrue(managementService.listWebhookDeliveries(installation.pid(), "dead_letter", 50).isEmpty());
        assertEquals("pending", managementService.listWebhookDeliveries(installation.pid(), null, 50)
                .getFirst().status());
    }
}
