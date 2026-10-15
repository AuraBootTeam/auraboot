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
import com.auraboot.framework.openplatform.security.OpenPlatformBearerAuthenticator;
import com.auraboot.framework.openplatform.security.OpenApiRequestIdFilter;
import com.auraboot.framework.application.security.ExternalMachineAuthException;
import org.springframework.mock.web.MockHttpServletRequest;
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
    @Autowired private IntegrationTokenService integrationTokens;
    @Autowired private OpenPlatformAuthMapper authMapper;
    @Autowired private OpenApiRateLimitMapper rateLimitMapper;
    @Autowired private OpenPlatformBearerAuthenticator authenticator;
    @Autowired private OpenPlatformSecretCodec secretCodec;
    @Autowired private OpenApiEventPublisher eventPublisher;
    @Autowired private OpenPlatformRetentionWorker retentionWorker;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private org.mybatis.spring.SqlSessionTemplate sqlSessionTemplate;
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentPermanentTokenRotationCreatesExactlyOneReplacement() throws Exception {
        var app = managementService.createApplication(new CreateApplicationRequest("Concurrent token rotation", "Serialized replacement"));
        var installation = managementService.install(app.pid(), new InstallApplicationRequest("development", Set.of("openapi.profile.read"), 600));
        var old = integrationTokens.create(installation.pid(), new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Create("ERP", "never", null), "initial-concurrent-1");
        var membership = tenantMemberService.findByTenantIdAndUserId(getTestTenant().getId(), getTestUser().getId());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> rotateIntegrationAsOwner(installation.pid(), old.metadata().pid(), "rotation-concurrent-a", membership, start));
            var second = executor.submit(() -> rotateIntegrationAsOwner(installation.pid(), old.metadata().pid(), "rotation-concurrent-b", membership, start));
            start.countDown();
            assertEquals(1, java.util.stream.Stream.of(first.get(), second.get()).filter(Boolean.TRUE::equals).count());
        }
        assertEquals(2, integrationTokens.list(installation.pid()).size());
        assertEquals(1, integrationTokens.list(installation.pid()).stream().filter(t -> "active".equals(t.status())).count());
        assertEquals(1, integrationTokens.list(installation.pid()).stream().filter(t -> "replaced".equals(t.status())).count());
        assertThrows(ExternalMachineAuthException.class, () -> authenticateIntegration(old.token(), "/api/open/v1/whoami"));
    }

    private boolean rotateIntegrationAsOwner(String installationPid, String oldPid, String key,
                                              TenantMember membership, CountDownLatch start) throws InterruptedException {
        start.await();
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
        MetaContext.setMemberId(membership.getId());
        try {
            integrationTokens.rotate(installationPid, oldPid, new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Rotate("never", null, 0), key);
            return true;
        } catch (org.springframework.web.server.ResponseStatusException conflict) {
            assertEquals(409, conflict.getStatusCode().value());
            return false;
        } finally { MetaContext.clear(); }
    }

    @Test
    void integrationTokensCannotBeManagedThroughAnotherInstallationOrAfterDisable() {
        var app = managementService.createApplication(new CreateApplicationRequest("Token parent scope", "Cross-parent refusal"));
        var one = managementService.install(app.pid(), new InstallApplicationRequest("development", Set.of("openapi.profile.read"), 600));
        var two = managementService.install(app.pid(), new InstallApplicationRequest("staging", Set.of("openapi.profile.read"), 600));
        var permanent = integrationTokens.create(one.pid(), new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Create("ERP", "never", null), "cross-parent-create");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> integrationTokens.revoke(two.pid(), permanent.metadata().pid()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> integrationTokens.rotate(two.pid(), permanent.metadata().pid(),
                new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Rotate("never", null, 0), "cross-parent-rotate"));
        authenticateIntegration(permanent.token(), "/api/open/v1/whoami");
        assertEquals(1, integrationTokens.list(one.pid()).size());
        assertTrue(integrationTokens.list(two.pid()).isEmpty());
        managementService.disableInstallation(one.pid());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> integrationTokens.create(one.pid(),
                new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Create("ERP", "never", null), "disabled-create-1"));
        assertThrows(ExternalMachineAuthException.class, () -> authenticateIntegration(permanent.token(), "/api/open/v1/whoami"));
    }

    @Test
    void integrationTokenKeepsItsIdentityWhileCurrentInstallationGrantsChange() {
        var app = managementService.createApplication(new CreateApplicationRequest("Quick access", "Current authority"));
        var installation = managementService.install(app.pid(), new InstallApplicationRequest("development", Set.of("openapi.profile.read"), 600));
        var permanent = integrationTokens.create(installation.pid(),
                new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Create("ERP", "never", null), "create-permanent-1");
        assertEquals(app.pid(), permanent.appId());
        assertEquals("never", permanent.metadata().expirationMode());
        assertNull(permanent.metadata().expiresAt());
        assertFalse(permanent.toString().contains(permanent.token()));
        var first = authenticateIntegration(permanent.token(), "/api/open/v1/whoami");
        assertEquals(installation.pid(), first.installationPid());
        assertEquals(Set.of("openapi.profile.read"), first.scopes());
        assertEquals(secretCodec.sha256(permanent.token()), jdbcTemplate.queryForObject(
                "SELECT token_hash FROM ab_application_access_token WHERE pid=?", String.class, permanent.metadata().pid()));
        assertNull(jdbcTemplate.queryForObject("SELECT credential_id FROM ab_application_access_token WHERE pid=?", Long.class, permanent.metadata().pid()));
        var credential = managementService.createCredential(installation.pid());
        var oauth = tokenService.issue("client_credentials", credential.clientId(), credential.clientSecret(), "openapi.profile.read");
        managementService.updateScopes(installation.pid(), new UpdateInstallationScopesRequest(Set.of("openapi.events.read")));
        var removed = assertThrows(ExternalMachineAuthException.class, () -> authenticateIntegration(permanent.token(), "/api/open/v1/whoami"));
        assertEquals(403, removed.status());
        assertEquals(Set.of("openapi.events.read"), authenticateIntegration(permanent.token(), "/api/open/v1/event-catalog").scopes());
        assertThrows(ExternalMachineAuthException.class, () -> authenticateIntegration(oauth.accessToken(), "/api/open/v1/whoami"));
        assertNull(jdbcTemplate.queryForObject("SELECT revoked_at FROM ab_application_access_token WHERE pid=?", java.sql.Timestamp.class, permanent.metadata().pid()));
        assertEquals(1, integrationTokens.list(installation.pid()).size());
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .valueToTree(integrationTokens.list(installation.pid())).toString().contains(permanent.token()));
    }

    @Test
    void permanentRotationHasFiniteCutoffAndRevocationDoesNotRevokeSiblings() {
        var app = managementService.createApplication(new CreateApplicationRequest("Rotation", "Permanent cutoff"));
        var installation = managementService.install(app.pid(), new InstallApplicationRequest("staging", Set.of("openapi.profile.read"), 600));
        var request = new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Create("ERP", "never", null);
        var old = integrationTokens.create(installation.pid(), request, "create-rotation-1");
        var sibling = integrationTokens.create(installation.pid(), request, "create-sibling-1");
        var rotated = integrationTokens.rotate(installation.pid(), old.metadata().pid(),
                new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Rotate("never", null, 0), "rotate-permanent-1");
        assertEquals(old.appId(), rotated.credential().appId());
        assertNull(rotated.credential().metadata().expiresAt());
        assertTrue(rotated.replacedTokenAcceptUntil().isBefore(Instant.now().plusSeconds(1)));
        assertEquals("replaced", integrationTokens.list(installation.pid()).stream().filter(t -> t.pid().equals(old.metadata().pid())).findFirst().orElseThrow().status());
        assertThrows(ExternalMachineAuthException.class, () -> authenticateIntegration(old.token(), "/api/open/v1/whoami"));
        authenticateIntegration(rotated.credential().token(), "/api/open/v1/whoami");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> integrationTokens.rotate(installation.pid(), old.metadata().pid(),
                new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Rotate("never", null, 0), "rotate-permanent-1"));
        assertEquals(3, integrationTokens.list(installation.pid()).size());
        integrationTokens.revoke(installation.pid(), rotated.credential().metadata().pid());
        integrationTokens.revoke(installation.pid(), rotated.credential().metadata().pid());
        assertThrows(ExternalMachineAuthException.class, () -> authenticateIntegration(rotated.credential().token(), "/api/open/v1/whoami"));
        authenticateIntegration(sibling.token(), "/api/open/v1/whoami");
        managementService.disableInstallation(installation.pid());
        assertThrows(ExternalMachineAuthException.class, () -> authenticateIntegration(sibling.token(), "/api/open/v1/whoami"));
    }

    @Test
    void fixedRotationCannotExtendOriginalExpiryAndCreateRetryNeverRevealsAnotherSecret() {
        var app = managementService.createApplication(new CreateApplicationRequest("Fixed lifetime", "Idempotent administration"));
        var installation = managementService.install(app.pid(), new InstallApplicationRequest("development", Set.of("openapi.profile.read"), 600));
        Instant until = Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var request = new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Create("ERP", "fixed", until);
        var old = integrationTokens.create(installation.pid(), request, "create-fixed-token-1");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> integrationTokens.create(installation.pid(), request, "create-fixed-token-1"));
        assertEquals(1, integrationTokens.list(installation.pid()).size());
        var rotated = integrationTokens.rotate(installation.pid(), old.metadata().pid(),
                new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Rotate("never", null, 10080), "rotate-fixed-token-1");
        assertEquals(until, rotated.replacedTokenAcceptUntil());
        authenticateIntegration(old.token(), "/api/open/v1/whoami");
        jdbcTemplate.update("UPDATE ab_application_access_token SET expires_at=?, issued_at=? WHERE pid=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), old.metadata().pid());
        assertThrows(ExternalMachineAuthException.class, () -> authenticateIntegration(old.token(), "/api/open/v1/whoami"));
        authenticateIntegration(rotated.credential().token(), "/api/open/v1/whoami");
    }

    private com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal authenticateIntegration(String token, String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer " + token);
        return authenticator.authenticate(request);
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
        var managedToken = integrationTokens.create(installation.pid(),
                new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Create("Managed token", "never", null), "role-create-token");

        switchActor(viewer, viewerMembership);
        assertEquals(1, managementService.listApplications().size());
        assertEquals(0, managementService.getOperationsOverview(installation.pid(), 24).totalCalls());
        assertThrows(AccessDeniedException.class,
                () -> managementService.createCredential(installation.pid()));
        assertThrows(AccessDeniedException.class, () -> integrationTokens.list(installation.pid()));
        assertThrows(AccessDeniedException.class, () -> integrationTokens.create(installation.pid(),
                new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Create("Denied", "never", null), "role-create-denied"));
        assertThrows(AccessDeniedException.class, () -> integrationTokens.rotate(installation.pid(), managedToken.metadata().pid(),
                new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Rotate("never", null, 0), "role-rotate-denied"));
        assertThrows(AccessDeniedException.class, () -> integrationTokens.revoke(installation.pid(), managedToken.metadata().pid()));


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
        int existingApplications = managementService.listApplications().size();
        var application = managementService.createApplication(
                new CreateApplicationRequest("Administrator override", "global administration boundary"));
        User replacementOwner = createTenantUser("replacement-owner");
        managementService.upsertMember(application.pid(), replacementOwner.getPid(),
                new UpsertApplicationMemberRequest("owner"));
        managementService.removeMember(application.pid(), getTestUser().getPid());

        assertEquals(existingApplications + 1, managementService.listApplications().size());
        assertEquals("owner", managementService.listApplications().stream()
                .filter(candidate -> candidate.pid().equals(application.pid()))
                .findFirst().orElseThrow().accessRole());
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
    void authenticationCommitsQuotaAndTokenUseWithoutTouchingDeniedRequests() {
        var application = managementService.createApplication(
                new CreateApplicationRequest("Atomic authentication", "committed quota boundary"));
        Long applicationId = jdbcTemplate.queryForObject(
                "SELECT id FROM ab_external_application WHERE pid = ?", Long.class, application.pid());
        try {
            var installation = managementService.install(application.pid(), new InstallApplicationRequest(
                    "production", Set.of("openapi.profile.read"), 1));
            var credential = managementService.createCredential(installation.pid());
            var token = tokenService.issue("client_credentials", credential.clientId(),
                    credential.clientSecret(), "openapi.profile.read");
            var record = authMapper.findToken(secretCodec.sha256(token.accessToken()),
                    OpenPlatformTokenService.AUDIENCE, Instant.now());
            Instant deniedAt = Instant.now();
            assertNull(rateLimitMapper.authenticate(secretCodec.sha256(token.accessToken()),
                    OpenPlatformTokenService.AUDIENCE, deniedAt,
                    deniedAt.truncatedTo(java.time.temporal.ChronoUnit.MINUTES),
                    "openapi.events.read").consumedCount());
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ab_open_api_rate_window WHERE installation_id = ?",
                    Integer.class, record.installationId()));
            assertNull(jdbcTemplate.queryForObject(
                    "SELECT last_used_at FROM ab_application_access_token WHERE pid = ?",
                    java.sql.Timestamp.class, record.tokenPid()));
            MockHttpServletRequest allowed = new MockHttpServletRequest("GET", "/api/open/v1/whoami");
            allowed.addHeader("Authorization", "Bearer " + token.accessToken());
            allowed.addHeader("X-Tenant-Id", "99999");
            allowed.setAttribute(OpenApiRequestIdFilter.REQUEST_ID_ATTRIBUTE, "atomic-auth-trace");
            assertTrue(authenticator.supports(allowed));
            assertFalse(authenticator.supports(new MockHttpServletRequest("GET", "/api/internal")));
            var principal = authenticator.authenticate(allowed);
            assertEquals(installation.pid(), principal.installationPid());
            assertEquals(getTestTenant().getId(), principal.tenantId());
            authenticator.recordCall(principal, allowed, 200, 12);
            var audit = jdbcTemplate.queryForMap("""
                    SELECT tenant_id, application_pid, installation_pid, token_pid,
                           request_id, http_method, request_path, response_status, duration_ms
                    FROM ab_open_api_call_audit WHERE installation_pid = ?
                    """, installation.pid());
            assertEquals(getTestTenant().getId(), audit.get("tenant_id"));
            assertEquals(application.pid(), audit.get("application_pid"));
            assertEquals(installation.pid(), audit.get("installation_pid"));
            assertEquals(record.tokenPid(), audit.get("token_pid"));
            assertEquals("atomic-auth-trace", audit.get("request_id"));
            assertEquals("GET", audit.get("http_method"));
            assertEquals("/api/open/v1/whoami", audit.get("request_path"));
            assertEquals(200, audit.get("response_status"));
            assertEquals(12L, audit.get("duration_ms"));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT SUM(request_count)::int FROM ab_open_api_rate_window WHERE installation_id = ?",
                    Integer.class, record.installationId()));
            var usedAt = jdbcTemplate.queryForObject(
                    "SELECT last_used_at FROM ab_application_access_token WHERE pid = ?",
                    java.sql.Timestamp.class, record.tokenPid());
            assertTrue(usedAt != null);

            MockHttpServletRequest denied = new MockHttpServletRequest("GET", "/api/open/v1/event-catalog");
            denied.addHeader("Authorization", "Bearer " + token.accessToken());
            assertEquals(403, assertThrows(ExternalMachineAuthException.class,
                    () -> authenticator.authenticate(denied)).status());
            var window = jdbcTemplate.queryForObject(
                    "SELECT window_start FROM ab_open_api_rate_window WHERE installation_id = ?",
                    java.sql.Timestamp.class, record.installationId()).toInstant();
            assertNull(rateLimitMapper.authenticate(secretCodec.sha256(token.accessToken()),
                    OpenPlatformTokenService.AUDIENCE, usedAt.toInstant().plusSeconds(1), window,
                    "openapi.profile.read").consumedCount());
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT SUM(request_count)::int FROM ab_open_api_rate_window WHERE installation_id = ?",
                    Integer.class, record.installationId()));
            assertEquals(usedAt, jdbcTemplate.queryForObject(
                    "SELECT last_used_at FROM ab_application_access_token WHERE pid = ?",
                    java.sql.Timestamp.class, record.tokenPid()));
            managementService.disableInstallation(installation.pid());
            assertEquals(401, assertThrows(ExternalMachineAuthException.class,
                    () -> authenticator.authenticate(allowed)).status());
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT SUM(request_count)::int FROM ab_open_api_rate_window WHERE installation_id = ?",
                    Integer.class, record.installationId()));
            assertEquals(usedAt, jdbcTemplate.queryForObject(
                    "SELECT last_used_at FROM ab_application_access_token WHERE pid = ?",
                    java.sql.Timestamp.class, record.tokenPid()));
        } finally {
            deleteCommittedApplicationFixture(applicationId);
        }
    }

    private void deleteCommittedApplicationFixture(Long applicationId) {
        jdbcTemplate.update("DELETE FROM ab_open_api_call_audit WHERE installation_pid IN "
            + "(SELECT pid FROM ab_application_installation WHERE application_id = ?)", applicationId);
        jdbcTemplate.update("DELETE FROM ab_open_api_rate_window WHERE installation_id IN "
                + "(SELECT id FROM ab_application_installation WHERE application_id = ?)", applicationId);
        jdbcTemplate.update("DELETE FROM ab_application_access_token WHERE installation_id IN "
                + "(SELECT id FROM ab_application_installation WHERE application_id = ?)", applicationId);
        jdbcTemplate.update("DELETE FROM ab_application_credential WHERE installation_id IN "
                + "(SELECT id FROM ab_application_installation WHERE application_id = ?)", applicationId);
        jdbcTemplate.update("DELETE FROM ab_application_scope_grant WHERE installation_id IN "
                + "(SELECT id FROM ab_application_installation WHERE application_id = ?)", applicationId);
        jdbcTemplate.update("DELETE FROM ab_application_installation WHERE application_id = ?", applicationId);
        jdbcTemplate.update("DELETE FROM ab_external_application_member_audit WHERE application_id = ?",
                applicationId);
        jdbcTemplate.update("DELETE FROM ab_external_application_member WHERE application_id = ?", applicationId);
        jdbcTemplate.update("DELETE FROM ab_external_application WHERE id = ?", applicationId);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void installationQuotaIsExactAcrossConcurrentTokens() throws Exception {
        var application = managementService.createApplication(
                new CreateApplicationRequest("Concurrent quota", "shared installation token boundary"));
        Long applicationId = jdbcTemplate.queryForObject(
                "SELECT id FROM ab_external_application WHERE pid = ?", Long.class, application.pid());
        try {
            var installation = managementService.install(application.pid(), new InstallApplicationRequest(
                    "production", Set.of("openapi.profile.read"), 5));
            var credential = managementService.createCredential(installation.pid());
            var first = tokenService.issue("client_credentials", credential.clientId(),
                    credential.clientSecret(), "openapi.profile.read");
            var second = tokenService.issue("client_credentials", credential.clientId(),
                    credential.clientSecret(), "openapi.profile.read");
            Instant now = Instant.now();
            Instant window = now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(12)) {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int index = 0; index < 12; index++) {
                    String rawToken = index % 2 == 0 ? first.accessToken() : second.accessToken();
                    futures.add(executor.submit(() -> {
                        start.await();
                        return rateLimitMapper.authenticate(secretCodec.sha256(rawToken),
                                OpenPlatformTokenService.AUDIENCE, now, window,
                                "openapi.profile.read").consumedCount() != null;
                    }));
                }
                start.countDown();
                int accepted = 0;
                for (var future : futures) if (future.get()) accepted++;
                assertEquals(5, accepted);
            }
            assertEquals(5, jdbcTemplate.queryForObject("""
                    SELECT SUM(rate.request_count)::int FROM ab_open_api_rate_window rate
                    JOIN ab_application_installation installation ON installation.id = rate.installation_id
                    WHERE installation.pid = ?
                    """, Integer.class, installation.pid()));
        } finally {
            deleteCommittedApplicationFixture(applicationId);
        }
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
        verifyDisabledCredentialWritesAreNotFoundAndLeaveStateUnchanged(installation.pid(), credential.credentialPid());
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
        verifyDisabledCredentialWritesAreNotFoundAndLeaveStateUnchanged(secondInstallation.pid(), secondCredential.credentialPid());

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
        verifyRetentionKeepsValidInstallationData(application.pid(), installation.pid(), rotation.credential());
    }

    private void verifyDisabledCredentialWritesAreNotFoundAndLeaveStateUnchanged(
            String installationPid, String credentialPid) {
        var before = credentialLifecycleState(installationPid);
        assertTrue(((Number) before.get("credentials")).intValue() > 0);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(ResponseCode.NOT_FOUND, assertThrows(BusinessException.class,
                        () -> managementService.createCredential(installationPid)).getResponseCode()),
                () -> assertEquals(ResponseCode.NOT_FOUND, assertThrows(BusinessException.class,
                        () -> managementService.rotateCredential(installationPid, credentialPid,
                                new RotateCredentialRequest(15))).getResponseCode()),
                () -> assertEquals(before, credentialLifecycleState(installationPid)));
    }

    private Map<String, Object> credentialLifecycleState(String installationPid) {
        Long installationId = jdbcTemplate.queryForObject(
                "SELECT id FROM ab_application_installation WHERE tenant_id=? AND pid=?",
                Long.class, getTestTenant().getId(), installationPid);
        var state = new java.util.LinkedHashMap<String, Object>();
        state.put("credentials", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_application_credential WHERE installation_id=?", Integer.class, installationId));
        state.put("credentialHash", jdbcTemplate.queryForObject(
                "SELECT md5(COALESCE(jsonb_agg(to_jsonb(c) ORDER BY c.id)::text, '[]')) "
                        + "FROM ab_application_credential c WHERE installation_id=?", String.class, installationId));
        state.put("tokenHash", jdbcTemplate.queryForObject(
                "SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY t.id)::text, '[]')) "
                        + "FROM ab_application_access_token t WHERE installation_id=?", String.class, installationId));
        return state;
    }

    private void verifyRetentionKeepsValidInstallationData(String applicationPid, String installationPid,
            com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CredentialSecret credential) {
        Long installationId = jdbcTemplate.queryForObject("SELECT id FROM ab_application_installation WHERE pid=?", Long.class, installationPid);
        Long tenantId = getTestTenant().getId();
        String oldToken = tokenService.issue("client_credentials", credential.clientId(), credential.clientSecret(), "assets.read").accessToken();
        String oldHash = secretCodec.sha256(oldToken);
        jdbcTemplate.update("UPDATE ab_application_access_token SET expires_at=NOW()-INTERVAL '8 days' WHERE token_hash=?", oldHash);
        jdbcTemplate.update("INSERT INTO ab_open_api_rate_window(installation_id,window_start,request_count) VALUES (?,NOW()-INTERVAL '3 days',1),(?,NOW()-INTERVAL '1 day',1)", installationId, installationId);
        String oldAudit = UniqueIdGenerator.generate(), keptAudit = UniqueIdGenerator.generate();
        jdbcTemplate.update("INSERT INTO ab_open_api_call_audit(pid,tenant_id,application_pid,installation_pid,http_method,request_path,response_status,duration_ms,occurred_at) "
                + "VALUES (?,?,?,?,'GET','/api/open/v1/whoami',200,1,NOW()-INTERVAL '91 days'),(?,?,?,?,'GET','/api/open/v1/whoami',200,1,NOW()-INTERVAL '89 days')",
                oldAudit, tenantId, applicationPid, installationPid, keptAudit, tenantId, applicationPid, installationPid);
        jdbcTemplate.update("INSERT INTO ab_open_api_idempotency(tenant_id,installation_id,route_code,idempotency_key,request_hash,expires_at) "
                + "VALUES (?,?,'retention','older-retention-key',?,NOW()-INTERVAL '8 days'),(?,?,'retention','grace-retention-key',?,NOW()-INTERVAL '6 days')",
                tenantId, installationId, secretCodec.sha256("old"), tenantId, installationId, secretCodec.sha256("grace"));
        int auditDays = (Integer) org.springframework.test.util.ReflectionTestUtils.getField(retentionWorker, "auditDays");
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(retentionWorker, "auditDays", 0);
            assertThrows(IllegalStateException.class, retentionWorker::purge);
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ab_open_api_call_audit WHERE pid=?", Integer.class, oldAudit));
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(retentionWorker, "auditDays", auditDays); }
        retentionWorker.purge();
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ab_application_access_token WHERE token_hash=?", Integer.class, oldHash));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ab_application_access_token WHERE installation_id=?", Integer.class, installationId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ab_open_api_rate_window WHERE installation_id=?", Integer.class, installationId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ab_open_api_call_audit WHERE pid=?", Integer.class, oldAudit));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ab_open_api_call_audit WHERE pid=?", Integer.class, keptAudit));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ab_open_api_idempotency WHERE installation_id=? AND idempotency_key='older-retention-key'", Integer.class, installationId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ab_open_api_idempotency WHERE installation_id=? AND idempotency_key='grace-retention-key'", Integer.class, installationId));
    }

    @Test
    void populatedOperationsQueriesAggregateSafelyAndReplayOnlyWithinInstallation() throws Exception {
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
                   max_retries, error_message, receiver_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'dead_letter', 3, 3, ?, 'https://example.invalid:443', NOW(), NOW())
                """, "delivery-ops-1", tenantId, "subscription-ops-1", installation.pid(), "event-ops-1",
                "https://secret.example.invalid/hook", "{\"secret\":\"must-not-leak\"}", 503,
                "private response body", "upstream https://secret.example.invalid failed");

        jdbcTemplate.update("INSERT INTO ab_webhook_receiver_lane(tenant_id,receiver_key) VALUES (?,?) ON CONFLICT DO NOTHING",
                tenantId, "https://example.invalid:443");


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
        jdbcTemplate.update("""
                UPDATE ab_webhook_delivery_log SET response_status = NULL, error_message = ?
                WHERE tenant_id = ? AND pid = ?
                """, "Published model event authority revoked or unavailable", tenantId, "delivery-ops-1");
        // Fixture JDBC writes bypass MyBatis; production attempts and reads use separate requests.
        sqlSessionTemplate.clearCache();
        assertEquals("authority_revoked", managementService.listWebhookDeliveries(
                installation.pid(), "dead_letter", 50).getFirst().failureReason());

        var otherInstallation = managementService.install(application.pid(),
                new InstallApplicationRequest("staging", Set.of("assets.read"), 600));
        assertThrows(IllegalStateException.class,
                () -> managementService.replayWebhookDelivery(otherInstallation.pid(), "delivery-ops-1"));
        // Manual replay overrides the receiver cooldown, but must preserve another in-flight lease.
        Instant future = Instant.now().plusSeconds(3600);
        jdbcTemplate.update("""
                UPDATE ab_webhook_receiver_lane SET ready_at=?,lease_token=?,lease_until=?
                WHERE tenant_id=? AND receiver_key=?
                """, java.sql.Timestamp.from(future), "other-active-attempt", java.sql.Timestamp.from(future),
                tenantId, "https://example.invalid:443");
        assertThrows(IllegalStateException.class,
                () -> managementService.replayWebhookDelivery(otherInstallation.pid(), "delivery-ops-1"));
        assertEquals(future.toEpochMilli(), jdbcTemplate.queryForObject(
                "SELECT ready_at FROM ab_webhook_receiver_lane WHERE tenant_id=? AND receiver_key=?",
                java.sql.Timestamp.class, tenantId, "https://example.invalid:443").toInstant().toEpochMilli());
        managementService.replayWebhookDelivery(installation.pid(), "delivery-ops-1");
        assertReplayClearsCooldownAndPreservesLease(tenantId, future);
        assertTrue(managementService.listWebhookDeliveries(installation.pid(), "dead_letter", 50).isEmpty());
        assertEquals("pending", managementService.listWebhookDeliveries(installation.pid(), null, 50)
                .getFirst().status());
        jdbcTemplate.update("UPDATE ab_webhook_delivery_log SET delivery_status='dead_letter' WHERE tenant_id=? AND pid=?",
                tenantId, "delivery-ops-1");
        jdbcTemplate.update("UPDATE ab_webhook_receiver_lane SET ready_at=? WHERE tenant_id=? AND receiver_key=?",
                java.sql.Timestamp.from(future), tenantId, "https://example.invalid:443");
        sqlSessionTemplate.clearCache();
        assertTrue(webhookService.replayDelivery("delivery-ops-1"));
        assertReplayClearsCooldownAndPreservesLease(tenantId, future);
        assertFalse(webhookService.replayDelivery("delivery-ops-1"));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT replay_count FROM ab_webhook_delivery_log WHERE tenant_id=? AND pid=?",
                Integer.class, tenantId, "delivery-ops-1"));
        var otherRequest = new WebhookCreateRequest();
        otherRequest.setName("Wrong replay parent");
        otherRequest.setTargetUrl("https://other.example.invalid/hook");
        otherRequest.setEventType("fixture.replay-parent");
        var otherSubscription = webhookService.create(otherRequest);
        jdbcTemplate.update("UPDATE ab_webhook_delivery_log SET delivery_status='dead_letter' WHERE tenant_id=? AND pid=?",
                tenantId, "delivery-ops-1");
        jdbcTemplate.update("UPDATE ab_webhook_receiver_lane SET ready_at=? WHERE tenant_id=? AND receiver_key=?",
                java.sql.Timestamp.from(future), tenantId, "https://example.invalid:443");
        sqlSessionTemplate.clearCache();
        var beforeWrongParent = jdbcTemplate.queryForMap(
                "SELECT * FROM ab_webhook_delivery_log WHERE tenant_id=? AND pid=?", tenantId, "delivery-ops-1");
        var beforeWrongLane = jdbcTemplate.queryForMap(
                "SELECT * FROM ab_webhook_receiver_lane WHERE tenant_id=? AND receiver_key=?",
                tenantId, "https://example.invalid:443");
        // Real controller/service/PG with a trusted fixture context; this does not exercise authentication filters.
        var replayHttp = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.auraboot.framework.webhook.controller.WebhookController(webhookService,
                        applicationContext.getBean(com.auraboot.framework.common.crypto.FieldEncryptionService.class)))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                        applicationContext.getBean(com.fasterxml.jackson.databind.ObjectMapper.class))).build();
        replayHttp.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/webhooks/" + otherSubscription.getPid() + "/deliveries/delivery-ops-1/replay"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value(org.hamcrest.Matchers.not("0")));
        assertEquals(beforeWrongParent, jdbcTemplate.queryForMap(
                "SELECT * FROM ab_webhook_delivery_log WHERE tenant_id=? AND pid=?", tenantId, "delivery-ops-1"));
        assertEquals(beforeWrongLane, jdbcTemplate.queryForMap(
                "SELECT * FROM ab_webhook_receiver_lane WHERE tenant_id=? AND receiver_key=?",
                tenantId, "https://example.invalid:443"));
        String correctRoute = "/api/webhooks/subscription-ops-1/deliveries/delivery-ops-1/replay";
        replayHttp.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(correctRoute))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("0"));
        assertReplayClearsCooldownAndPreservesLease(tenantId, future);
        assertEquals("pending", jdbcTemplate.queryForObject(
                "SELECT delivery_status FROM ab_webhook_delivery_log WHERE tenant_id=? AND pid=?",
                String.class, tenantId, "delivery-ops-1"));
        replayHttp.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(correctRoute))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value(org.hamcrest.Matchers.not("0")));
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT replay_count FROM ab_webhook_delivery_log WHERE tenant_id=? AND pid=?",
                Integer.class, tenantId, "delivery-ops-1"));
    }

    private void assertReplayClearsCooldownAndPreservesLease(Long tenantId, Instant leaseUntil) {
        var lane = jdbcTemplate.queryForMap(
                "SELECT ready_at,lease_token,lease_until FROM ab_webhook_receiver_lane WHERE tenant_id=? AND receiver_key=?",
                tenantId, "https://example.invalid:443");
        assertTrue(((java.sql.Timestamp) lane.get("ready_at")).toInstant().isBefore(Instant.now()));
        assertEquals("other-active-attempt", lane.get("lease_token"));
        assertEquals(leaseUntil.toEpochMilli(), ((java.sql.Timestamp) lane.get("lease_until")).toInstant().toEpochMilli());
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
        eventPublisher.publish("assets.assignment.changed", 1, "asset-public-pid",
                Map.of("pid", "asset-public-pid", "assetCode", "A-001", "secret", "never-publish"), tenantId);
        var publishedBodies = jdbcTemplate.queryForList("SELECT request_body FROM ab_webhook_delivery_log WHERE installation_pid=?", String.class, installation.pid());
        assertFalse(publishedBodies.isEmpty());
        assertTrue(publishedBodies.stream().allMatch(body -> body.contains("asset-public-pid") && body.contains("A-001")));
        assertFalse(publishedBodies.stream().anyMatch(body -> body.contains("never-publish") || body.contains("secret")));
        assertThrows(IllegalArgumentException.class, () -> eventPublisher.publish("platform.command.executed", 1,
                "asset-public-pid", Map.of(), tenantId));
        assertEquals(publishedBodies.size(), jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ab_webhook_delivery_log WHERE installation_pid=?", Integer.class, installation.pid()));
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

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRateConsumptionNeverExceedsTheLimitAndCommitsTokenUsage() throws Exception {
        var app = managementService.createApplication(
                new CreateApplicationRequest("Concurrent atomic rate accounting", "preserved PostgreSQL fixture"));
        Long applicationId = jdbcTemplate.queryForObject(
                "SELECT id FROM ab_external_application WHERE pid = ?", Long.class, app.pid());
        try {
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
                        return rateLimitMapper.authenticate(secretCodec.sha256(issued.accessToken()), OpenPlatformTokenService.AUDIENCE, use, window, "openapi.profile.read").consumedCount()
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
        } finally {
            deleteCommittedApplicationFixture(applicationId);
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
        assertEquals(1, rateLimitMapper.authenticate(secretCodec.sha256(issued.accessToken()), OpenPlatformTokenService.AUDIENCE, newer, window, "openapi.profile.read").consumedCount());
        assertEquals(2, rateLimitMapper.authenticate(secretCodec.sha256(issued.accessToken()), OpenPlatformTokenService.AUDIENCE, newer.minusSeconds(1), window, "openapi.profile.read").consumedCount());
        assertEquals(newer, jdbcTemplate.queryForObject(
                "SELECT last_used_at FROM ab_application_access_token WHERE pid = ?",
                (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), token.tokenPid()));
        assertNull(rateLimitMapper.authenticate(secretCodec.sha256(issued.accessToken()), OpenPlatformTokenService.AUDIENCE, newer.plusSeconds(1), window, "openapi.profile.read").consumedCount());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT request_count FROM ab_open_api_rate_window WHERE installation_id = ? AND window_start = ?",
                Integer.class, token.installationId(), java.sql.Timestamp.from(window)));
        assertEquals(newer, jdbcTemplate.queryForObject(
                "SELECT last_used_at FROM ab_application_access_token WHERE pid = ?",
                (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), token.tokenPid()));
    }
}
