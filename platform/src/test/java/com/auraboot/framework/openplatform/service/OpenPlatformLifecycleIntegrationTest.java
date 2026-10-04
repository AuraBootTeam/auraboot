package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CreateApplicationRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.InstallApplicationRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.UpdateInstallationScopesRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.RotateCredentialRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.UpsertApplicationMemberRequest;
import com.auraboot.framework.openplatform.mapper.OpenPlatformAuthMapper;
import com.auraboot.framework.openplatform.mapper.OpenApiRateLimitMapper;
import com.auraboot.framework.webhook.dto.WebhookCreateRequest;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.auraboot.framework.webhook.service.WebhookService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenPlatformLifecycleIntegrationTest extends BaseIntegrationTest {

    @Autowired private OpenPlatformManagementService managementService;
    @Autowired private OpenPlatformTokenService tokenService;
    @Autowired private OpenPlatformAuthMapper authMapper;
    @Autowired private OpenApiRateLimitMapper rateLimitMapper;
    @Autowired private OpenPlatformSecretCodec secretCodec;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private WebhookService webhookService;
    @Autowired private WebhookDispatcher webhookDispatcher;
    @Autowired private UserService userService;
    @Autowired private TenantMemberService tenantMemberService;

    @BeforeEach
    void grantOpenPlatformAdministration() {
        grantCommittedPermissionToTestRole("sys.connector.update", "system", "connector", "update",
                "Manage Open Platform");
    }

    @Test
    void applicationRolesAuthorizeCollaborationAndProtectTheLastOwner() throws Exception {
        var application = managementService.createApplication(
                new CreateApplicationRequest("Collaboration fixture", "role boundary verification"));
        User maintainer = createTenantUser("maintainer");
        User viewer = createTenantUser("viewer");
        User outsider = createTenantUser("outsider");
        User nonTenantUser = userService.signUp(
                "non-tenant." + UniqueIdGenerator.generate().toLowerCase() + "@example.invalid",
                "test-password-123");
        TenantMember maintainerMembership = tenantMemberService.findByTenantIdAndUserId(
                getTestTenant().getId(), maintainer.getId());
        TenantMember viewerMembership = tenantMemberService.findByTenantIdAndUserId(
                getTestTenant().getId(), viewer.getId());

        managementService.upsertMember(application.pid(), maintainer.getPid(),
                new UpsertApplicationMemberRequest("maintainer"));
        managementService.upsertMember(application.pid(), viewer.getPid(),
                new UpsertApplicationMemberRequest("viewer"));
        managementService.upsertMember(application.pid(), maintainer.getPid(),
                new UpsertApplicationMemberRequest("viewer"));
        managementService.upsertMember(application.pid(), maintainer.getPid(),
                new UpsertApplicationMemberRequest("maintainer"));

        switchActor(maintainer, maintainerMembership);
        assertEquals(1, managementService.listApplications().size());
        var installation = managementService.install(application.pid(),
                new InstallApplicationRequest("production", Set.of("openapi.profile.read"), 600));
        managementService.createCredential(installation.pid());

        switchActor(viewer, viewerMembership);
        assertEquals(1, managementService.listApplications().size());
        assertEquals(0, managementService.getOperationsOverview(installation.pid(), 24).totalCalls());
        assertThrows(AccessDeniedException.class,
                () -> managementService.createCredential(installation.pid()));

        TenantMember outsiderMembership = tenantMemberService.findByTenantIdAndUserId(
                getTestTenant().getId(), outsider.getId());
        switchActor(outsider, outsiderMembership);
        assertTrue(managementService.listApplications().isEmpty());
        assertThrows(AccessDeniedException.class, () -> managementService.install(application.pid(),
                new InstallApplicationRequest("staging", Set.of("openapi.profile.read"), 600)));

        applyTestMetaContext();
        assertThrows(IllegalArgumentException.class, () -> managementService.upsertMember(
                application.pid(), nonTenantUser.getPid(), new UpsertApplicationMemberRequest("viewer")));
        assertThrows(BusinessException.class, () -> managementService.upsertMember(
                application.pid(), getTestUser().getPid(), new UpsertApplicationMemberRequest("viewer")));
        assertThrows(BusinessException.class,
                () -> managementService.removeMember(application.pid(), getTestUser().getPid()));
        assertEquals(2, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_external_application_member_audit audit
                JOIN ab_external_application app ON app.id = audit.application_id
                WHERE app.pid = ? AND audit.action = 'add' AND audit.target_user_pid <> ?
                """, Integer.class, application.pid(), getTestUser().getPid()));
        assertEquals(2, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_external_application_member_audit audit
                JOIN ab_external_application app ON app.id = audit.application_id
                WHERE app.pid = ? AND audit.action = 'update' AND audit.target_user_pid = ?
                """, Integer.class, application.pid(), maintainer.getPid()));
    }

    @Test
    void platformAdministratorOverridesApplicationMembership() throws Exception {
        var application = managementService.createApplication(
                new CreateApplicationRequest("Administrator override", "global administration boundary"));
        User replacementOwner = createTenantUser("replacement-owner");
        managementService.upsertMember(application.pid(), replacementOwner.getPid(),
                new UpsertApplicationMemberRequest("owner"));
        managementService.removeMember(application.pid(), getTestUser().getPid());

        var visibleApplication = managementService.listApplications().stream()
                .filter(entry -> entry.pid().equals(application.pid())).toList();
        assertEquals(1, visibleApplication.size());
        assertEquals("owner", visibleApplication.getFirst().accessRole());
        assertTrue(managementService.getAccess().platformAdmin());
        assertTrue(managementService.getAccess().canCreateApplications());
        assertEquals("production", managementService.install(application.pid(),
                new InstallApplicationRequest("production", Set.of("openapi.profile.read"), 600)).environment());
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_external_application_member_audit audit
                JOIN ab_external_application app ON app.id = audit.application_id
                WHERE app.pid = ? AND audit.action = 'remove' AND audit.target_user_pid = ?
                """, Integer.class, application.pid(), getTestUser().getPid()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentOwnerRemovalCannotLeaveApplicationOwnerless() throws Exception {
        var application = managementService.createApplication(
                new CreateApplicationRequest("Concurrent owner guard", "serialized owner removal"));
        String secondOwnerPid = "concurrent-owner-" + UniqueIdGenerator.generate().toLowerCase();
        Long applicationId = jdbcTemplate.queryForObject(
                "SELECT id FROM ab_external_application WHERE pid = ?", Long.class, application.pid());
        jdbcTemplate.update("""
                INSERT INTO ab_external_application_member
                    (tenant_id, application_id, user_pid, role, created_by_pid, updated_by_pid)
                VALUES (?, ?, ?, 'owner', ?, ?)
                """, getTestTenant().getId(), applicationId, secondOwnerPid,
                getTestUser().getPid(), getTestUser().getPid());
        TenantMember administratorMembership = tenantMemberService.findByTenantIdAndUserId(
                getTestTenant().getId(), getTestUser().getId());
        var start = new CountDownLatch(1);

        try {
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> removeOwnerAsAdministrator(
                        application.pid(), getTestUser().getPid(), administratorMembership, start));
                var second = executor.submit(() -> removeOwnerAsAdministrator(
                        application.pid(), secondOwnerPid, administratorMembership, start));
                start.countDown();

                assertEquals(1, java.util.stream.Stream.of(first.get(), second.get())
                        .filter(Boolean.TRUE::equals).count());
            }
            assertEquals(1, jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM ab_external_application_member member
                    JOIN ab_external_application app ON app.id = member.application_id
                    WHERE app.pid = ? AND member.role = 'owner'
                    """, Integer.class, application.pid()));
        } finally {
            jdbcTemplate.update("DELETE FROM ab_external_application_member_audit WHERE application_id = ?",
                    applicationId);
            jdbcTemplate.update("DELETE FROM ab_external_application_member WHERE application_id = ?",
                    applicationId);
            jdbcTemplate.update("DELETE FROM ab_external_application WHERE id = ?", applicationId);
        }
    }

    private boolean removeOwnerAsAdministrator(String applicationPid, String ownerPid,
                                               TenantMember administratorMembership,
                                               CountDownLatch start) throws InterruptedException {
        start.await();
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(),
                getTestUser().getPid(), getTestUser().getUserName());
        MetaContext.setMemberId(administratorMembership.getId());
        try {
            managementService.removeMember(applicationPid, ownerPid);
            return true;
        } catch (BusinessException expected) {
            return false;
        } finally {
            MetaContext.clear();
        }
    }

    private User createTenantUser(String label) throws Exception {
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        User user = userService.signUp(label + "." + suffix + "@example.invalid", "test-password-123");
        tenantMemberService.addMember(user.getId(), getTestTenant().getId(), "active");
        return user;
    }

    private void switchActor(User user, TenantMember member) {
        MetaContext.setContext(getTestTenant().getId(), user.getId(), user.getPid(), user.getUserName());
        MetaContext.setMemberId(member.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRateConsumptionNeverExceedsTheLimitAndCommitsTokenUsage() throws Exception {
        var app = managementService.createApplication(
                new CreateApplicationRequest("Concurrent atomic rate accounting", "preserved PostgreSQL fixture"));
        var installation = managementService.install(app.pid(), new InstallApplicationRequest(
                "production", Set.of("openapi.profile.read"), 5));
        var credential = managementService.createCredential(installation.pid());
        var issued = tokenService.issue("client_credentials", credential.clientId(),
                credential.clientSecret(), "openapi.profile.read");
        var token = authMapper.findToken(secretCodec.sha256(issued.accessToken()),
                OpenPlatformTokenService.AUDIENCE, Instant.now());
        Instant stamp = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant window = stamp.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Instant>>();
            for (int i = 0; i < 24; i++) {
                Instant use = stamp.plusMillis(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return rateLimitMapper.consume(token.installationId(), window, 5, token.tokenPid(), use)
                            == null ? null : use;
                }));
            }
            start.countDown();
            var accepted = new java.util.ArrayList<Instant>();
            for (var future : futures) {
                Instant use = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
                if (use != null) accepted.add(use);
            }
            assertEquals(5, accepted.size());
            assertEquals(5, jdbcTemplate.queryForObject(
                    "SELECT request_count FROM ab_open_api_rate_window WHERE installation_id = ? AND window_start = ?",
                    Integer.class, token.installationId(), java.sql.Timestamp.from(window)));
            assertEquals(accepted.stream().max(Instant::compareTo).orElseThrow(), jdbcTemplate.queryForObject(
                    "SELECT last_used_at FROM ab_application_access_token WHERE pid = ?",
                    (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), token.tokenPid()));
        }
    }

    @Test
    void rateConsumptionTouchesOnlyAcceptedRequestsAndKeepsUsageMonotonic() {
        var app = managementService.createApplication(
                new CreateApplicationRequest("Atomic rate accounting", "real PostgreSQL counter/token fixture"));
        var installation = managementService.install(app.pid(), new InstallApplicationRequest(
                "production", Set.of("openapi.profile.read"), 2));
        var credential = managementService.createCredential(installation.pid());
        var issued = tokenService.issue("client_credentials", credential.clientId(),
                credential.clientSecret(), "openapi.profile.read");
        var token = authMapper.findToken(secretCodec.sha256(issued.accessToken()),
                OpenPlatformTokenService.AUDIENCE, Instant.now());
        Instant window = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        Instant newer = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        assertEquals(1, rateLimitMapper.consume(token.installationId(), window, 2, token.tokenPid(), newer));
        assertEquals(2, rateLimitMapper.consume(token.installationId(), window, 2, token.tokenPid(), newer.minusSeconds(1)));
        assertEquals(newer, jdbcTemplate.queryForObject(
                "SELECT last_used_at FROM ab_application_access_token WHERE pid = ?",
                (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), token.tokenPid()));
        assertNull(rateLimitMapper.consume(token.installationId(), window, 2, token.tokenPid(), newer.plusSeconds(1)));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT request_count FROM ab_open_api_rate_window WHERE installation_id = ? AND window_start = ?",
                Integer.class, token.installationId(), java.sql.Timestamp.from(window)));
        assertEquals(newer, jdbcTemplate.queryForObject(
                "SELECT last_used_at FROM ab_application_access_token WHERE pid = ?",
                (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), token.tokenPid()));
    }

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
        BusinessException disabled = assertThrows(BusinessException.class,
                () -> managementService.install(application.pid(),
                        new InstallApplicationRequest("production", Set.of("openapi.profile.read"), 600)));
        assertEquals(ResponseCode.NOT_FOUND, disabled.getResponseCode());
        BusinessException repeated = assertThrows(BusinessException.class,
                () -> managementService.disableApplication(application.pid()));
        assertEquals(ResponseCode.NOT_FOUND, repeated.getResponseCode());
        BusinessException memberWrite = assertThrows(BusinessException.class,
                () -> managementService.upsertMember(application.pid(), getTestUser().getPid(),
                        new UpsertApplicationMemberRequest("viewer")));
        assertEquals(ResponseCode.NOT_FOUND, memberWrite.getResponseCode());
        BusinessException missing = assertThrows(BusinessException.class,
                () -> managementService.install("missing-application",
                        new InstallApplicationRequest("staging", Set.of("openapi.profile.read"), 600)));
        assertEquals(ResponseCode.NOT_FOUND, missing.getResponseCode());
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

    @Test
    void installationWebhookCatalogCompatibilityAndRotationHealthUseRealPostgres() {
        var application = managementService.createApplication(
                new CreateApplicationRequest("Versioned webhook fixture", null));
        var installation = managementService.install(application.pid(),
                new InstallApplicationRequest("production", Set.of("openapi.events.read"), 600));
        Long tenantId = MetaContext.getCurrentTenantId();

        WebhookCreateRequest valid = new WebhookCreateRequest();
        valid.setName("Asset partner hook");
        valid.setInstallationPid(installation.pid());
        valid.setTargetUrl("https://example.invalid/assets");
        valid.setEventType("assets.assignment.changed");
        valid.setEventVersion(1);
        valid.setSecret("rotated-secret");
        var created = webhookService.create(valid);
        assertEquals(1, created.getEventVersion());
        assertTrue(created.getSecretRotatedAt().isAfter(Instant.now().minusSeconds(10)));

        WebhookCreateRequest incompatible = new WebhookCreateRequest();
        incompatible.setName("Unsupported hook");
        incompatible.setInstallationPid(installation.pid());
        incompatible.setTargetUrl("https://example.invalid/unsupported");
        incompatible.setEventType("assets.assignment.changed");
        incompatible.setEventVersion(2);
        incompatible.setSecret("secret");
        assertThrows(IllegalArgumentException.class, () -> webhookService.create(incompatible));

        Instant now = Instant.now();
        insertWebhookHealthRow(tenantId, installation.pid(), "hook-due", "Due hook",
                "assets.assignment.changed", 1, "secret", now.minus(80, java.time.temporal.ChronoUnit.DAYS));
        insertWebhookHealthRow(tenantId, installation.pid(), "hook-overdue", "Overdue hook",
                "inventory.stock-in.confirmed", 1, "secret", now.minus(91, java.time.temporal.ChronoUnit.DAYS));
        insertWebhookHealthRow(tenantId, installation.pid(), "hook-missing", "Unsigned hook",
                "assets.assignment.changed", 1, null, null);
        insertWebhookHealthRow(tenantId, installation.pid(), "hook-incompatible", "Removed event hook",
                "removed.partner.event", 1, "secret", now);

        var health = managementService.listWebhookHealth(installation.pid());
        assertEquals(5, health.size());
        assertTrue(health.stream().anyMatch(item -> item.pid().equals(created.getPid())
                && item.compatible() && item.rotationStatus().equals("healthy")));
        assertTrue(health.stream().anyMatch(item -> item.pid().equals("hook-due")
                && item.rotationStatus().equals("due")));
        assertTrue(health.stream().anyMatch(item -> item.pid().equals("hook-overdue")
                && item.rotationStatus().equals("overdue")));
        assertTrue(health.stream().anyMatch(item -> item.pid().equals("hook-missing")
                && item.rotationStatus().equals("missing")));
        assertTrue(health.stream().anyMatch(item -> item.pid().equals("hook-incompatible")
                && !item.compatible()));
        assertFalse(health.toString().contains("rotated-secret"));
        assertFalse(health.toString().contains("example.invalid"));

        var rejected = webhookDispatcher.dispatchTracked("assets.assignment.changed", Map.of(
                "id", "evt_invalid",
                "type", "assets.assignment.changed",
                "schemaVersion", 1,
                "occurredAt", Instant.now().toString(),
                "subject", Map.of("type", "assets", "pid", "asset-1"),
                "data", Map.of("pid", "asset-1", "secret", "must-not-leave-boundary")), tenantId);
        assertTrue(rejected.receipts().isEmpty());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_webhook_delivery_log WHERE event_id = 'evt_invalid'", Integer.class));
    }

    private void insertWebhookHealthRow(Long tenantId, String installationPid, String pid, String name,
                                        String eventType, int eventVersion, String secret, Instant rotatedAt) {
        jdbcTemplate.update("""
                INSERT INTO ab_webhook_subscription
                  (tenant_id, pid, name, target_url, event_type, event_version, secret,
                   secret_rotated_at, installation_pid, created_at, updated_at)
                VALUES (?, ?, ?, 'https://must-not-leak.invalid/hook', ?, ?, ?, ?, ?, NOW(), NOW())
                """, tenantId, pid, name, eventType, eventVersion, secret,
                rotatedAt == null ? null : java.sql.Timestamp.from(rotatedAt), installationPid);
    }
}
