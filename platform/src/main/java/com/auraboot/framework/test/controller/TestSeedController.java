package com.auraboot.framework.test.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.test.dto.SeedResult;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.auraboot.framework.common.constant.StatusConstants;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Test seed controller for E2E tests (Playwright + iOS XCUITest).
 * <p>
 * Only active when the "test" Spring profile is enabled.
 * Provides endpoints to create/reset a test tenant with an admin user
 * and return a ready-to-use JWT token.
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
@Profile("test")
@RequiredArgsConstructor
public class TestSeedController {

    private static final String TEST_TENANT_NAME = "e2e_test";
    private static final String TEST_USER_EMAIL = "e2e@test.local";
    private static final String TEST_USER_PASSWORD = "E2eTestPass2026!";
    private static final String TEST_USER_DISPLAY_NAME = "E2E Test Admin";

    private final TenantService tenantService;
    private final TenantBootstrapService tenantBootstrapService;
    private final TenantMemberService tenantMemberService;
    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final SessionManagementService sessionManagementService;
    private final PluginImportService pluginImportService;

    /**
     * POST /api/test/seed
     * <p>
     * Create a test tenant with an admin user if it doesn't exist.
     * If it already exists, return a fresh JWT for the existing user.
     * Accepts optional testRunId query param; generates one if absent.
     */
    @PostMapping("/seed")
    public ResponseEntity<SeedResult> seed(
            @RequestParam(value = "testRunId", required = false) String testRunId) {
        String runId = testRunId != null ? testRunId : generateTestRunId("api");
        log.info("Test seed requested: testRunId={}", runId);

        // 1. Check if test tenant already exists
        Tenant tenant = tenantService.findByName(TEST_TENANT_NAME);
        User user = userService.findByEmail(TEST_USER_EMAIL);

        boolean tenantExists = tenant != null;
        boolean userExists = user != null;

        // 2. Create user if not exists
        if (!userExists) {
            user = userService.signUp(TEST_USER_EMAIL, TEST_USER_PASSWORD, TEST_USER_DISPLAY_NAME);
            log.info("Test user created: userId={}, email={}", user.getId(), TEST_USER_EMAIL);
        }

        // 3. Create tenant if not exists
        if (!tenantExists) {
            tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(TEST_TENANT_NAME);
            tenant.setDisplayName("E2E Test Tenant");
            tenant.setStatus(StatusConstants.ACTIVE);
            tenant.setContactEmail(TEST_USER_EMAIL);
            tenant.setCreatedBy(user.getId());
            tenant.setUpdatedBy(user.getId());
            tenant = tenantService.createTenant(tenant);
            log.info("Test tenant created: tenantId={}", tenant.getId());
        }

        // 4. Create tenant membership
        TenantMember existingMember = tenantMemberService.findByTenantIdAndUserId(tenant.getId(), user.getId());
        if (existingMember == null) {
            tenantMemberService.addMember(user.getId(), tenant.getId(), "active");
            log.info("Test user added as tenant member: tenantId={}, userId={}", tenant.getId(), user.getId());
        }

        // 5. Bootstrap only for a newly-created tenant. Existing tenants may already
        // contain seed menus/roles, and re-running bootstrap is not idempotent.
        if (!tenantExists) {
            TenantBootstrapService.BootstrapResult bootstrapResult =
                    tenantBootstrapService.bootstrapTenant(tenant.getId(), user.getId());
            log.info("Tenant bootstrap completed: success={}, roles={}, menus={}, permissions={}, duration={}ms",
                    bootstrapResult.isSuccess(),
                    bootstrapResult.getRolesCreated(),
                    bootstrapResult.getMenusCreated(),
                    bootstrapResult.getPermissionsAssigned(),
                    bootstrapResult.getDurationMs());
        } else {
            log.info("Test tenant already exists, skipping bootstrap and only refreshing plugin state");
        }

        // 5.5 Install test-fixtures plugin into the new test tenant so iOS/Playwright
        // tests can navigate to browse_e2et_order_list without manual intervention.
        installE2eTestPlugin(tenant, user);

        // 6. Generate JWT
        String jwt = generateJwt(user, tenant.getId());

        if (tenantExists && userExists) {
            log.info("Test tenant already existed; refreshed bootstrap/plugin state: tenantId={}, userId={}",
                    tenant.getId(), user.getId());
        }

        SeedResult result = SeedResult.builder()
                .tenantId(tenant.getId())
                .userId(user.getId())
                .jwt(jwt)
                .email(TEST_USER_EMAIL)
                .tenantName(TEST_TENANT_NAME)
                .testRunId(runId)
                .build();

        log.info("Test seed completed: tenantId={}, userId={}", tenant.getId(), user.getId());
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/test/reset
     * <p>
     * Delete the test tenant and all its data, then re-run seed.
     */
    @PostMapping("/reset")
    public ResponseEntity<SeedResult> reset() {
        log.info("Test reset requested");

        // 1. Find and delete ALL existing test tenants (guard against duplicates
        //    that accumulate from failed partial resets in dev environments).
        int deletedCount = 0;
        Tenant staleTenant;
        while ((staleTenant = tenantService.findByName(TEST_TENANT_NAME)) != null) {
            var members = tenantMemberService.findByTenantId(staleTenant.getId());
            for (TenantMember member : members) {
                tenantMemberService.removeMember(member.getId());
            }
            tenantService.deleteTenant(staleTenant.getId());
            log.info("Test tenant deleted: tenantId={}", staleTenant.getId());
            deletedCount++;
            if (deletedCount > 20) {
                log.warn("Too many test tenants — stopping cleanup after 20 deletions");
                break;
            }
        }
        if (deletedCount > 0) {
            log.info("Deleted {} test tenant(s)", deletedCount);
        }

        // 2. Delete the test user (if exists and not used elsewhere)
        User user = userService.findByEmail(TEST_USER_EMAIL);
        if (user != null) {
            // Check if user belongs to other tenants
            var tenantIds = tenantMemberService.getTenantIdsByUserId(user.getId());
            if (tenantIds.isEmpty()) {
                // Safe to conceptually "remove" — but we just let seed recreate
                log.info("Test user has no other tenants, will be reused by seed");
            }
        }

        // 3. Re-run seed
        return seed(null);
    }

    /**
     * GET /api/test/context
     * <p>
     * Return current test state — useful for debugging and cross-test coordination.
     */
    @GetMapping("/context")
    public ResponseEntity<?> context() {
        Tenant tenant = tenantService.findByName(TEST_TENANT_NAME);
        User user = userService.findByEmail(TEST_USER_EMAIL);

        if (tenant == null || user == null) {
            return ResponseEntity.ok(Map.of(
                    "seeded", false,
                    "message", "Test environment not seeded. Call POST /api/test/seed first."
            ));
        }

        String jwt = generateJwt(user, tenant.getId());

        return ResponseEntity.ok(Map.of(
                "seeded", true,
                "tenantId", tenant.getId(),
                "userId", user.getId(),
                "email", TEST_USER_EMAIL,
                "tenantName", TEST_TENANT_NAME,
                "jwt", jwt
        ));
    }

    /**
     * Install the test-fixtures plugin into the given tenant so that iOS/Playwright
     * E2E tests can navigate to the e2et menu without manual plugin publishing.
     * <p>
     * The plugin directory is resolved relative to the Spring Boot working directory
     * ({@code user.dir}), which is the {@code platform/} module root at runtime.
     * The plugins directory sits one level above: {@code ../plugins/test-fixtures}.
     * <p>
     * Failure is non-fatal — logs a warning and continues so that seed never breaks
     * due to a missing plugin directory (e.g. CI environments without the full tree).
     */
    private void installE2eTestPlugin(Tenant tenant, User user) {
        MetaContext.setContext(tenant.getId(), user.getId(), user.getPid(), user.getEmail());
        try {
            importTestPlugin("../plugins/project-management", "project-management", tenant.getId());
            importTestPlugin("../plugins/test-fixtures", "test-fixtures", tenant.getId());
        } catch (Exception e) {
            log.warn("test-fixtures plugin install threw exception for tenant {}: {}",
                    tenant.getId(), e.getMessage());
        } finally {
            MetaContext.clear();
        }
    }

    private void importTestPlugin(String relativePath, String pluginName, Long tenantId) {
        Path pluginDir = Path.of(System.getProperty("user.dir"))
                .resolve(relativePath)
                .normalize();

        if (!pluginDir.toFile().isDirectory()) {
            log.warn("{} plugin directory not found at {}, skipping plugin install", pluginName, pluginDir);
            return;
        }

        ImportPreviewResult preview = pluginImportService.parseDirectory(pluginDir.toString());
        if (!preview.isValid()) {
            log.warn("{} plugin parse failed: {}", pluginName, preview.getErrors());
            return;
        }

        ImportRequest importRequest = ImportRequest.builder()
                .importId(preview.getImportId())
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                .autoPublishModels(true)
                .autoPublishFields(true)
                .autoPublishCommands(true)
                .autoPublishPages(true)
                .autoDeployProcesses(true)
                .build();
        var result = pluginImportService.execute(preview.getImportId(), importRequest);
        if (result.isSuccess()) {
            log.info("{} plugin installed for tenant {}: counts={}",
                    pluginName, tenantId, result.getResourceCounts());
        } else {
            log.warn("{} plugin install failed for tenant {}: {}",
                    pluginName, tenantId, result.getErrorMessage());
        }
    }

    /**
     * GET /api/test/run-id?platform=web
     * <p>
     * Generate a testRunId conforming to the Test Session Contract format:
     * {platform}_{unixSeconds}_{4-hex-random}
     */
    @GetMapping("/run-id")
    public ResponseEntity<Map<String, String>> generateRunIdEndpoint(
            @RequestParam(value = "platform", defaultValue = "api") String platform) {
        return ResponseEntity.ok(Map.of("testRunId", generateTestRunId(platform)));
    }

    /**
     * Generate a testRunId per the Test Session Contract (section 5):
     * {platform}_{unixSeconds}_{4-hex-random}
     */
    static String generateTestRunId(String platform) {
        long ts = Instant.now().getEpochSecond();
        String hex = String.format("%04x", new SecureRandom().nextInt(0xFFFF));
        return platform + "_" + ts + "_" + hex;
    }

    /**
     * Generate a JWT token for the given user and tenant.
     */
    private String generateJwt(User user, Long tenantId) {
        TenantMember tenantMember = tenantMemberService.findByTenantIdAndUserId(tenantId, user.getId());
        if (tenantMember == null) {
            throw new IllegalStateException("Test seed user is not a member of tenant " + tenantId);
        }

        CustomUserDetails userDetails = new CustomUserDetails(
                user.getEmail(),
                user.getPassword() != null ? user.getPassword() : "",
                user.getId(),
                user.getPid(),
                Collections.singletonList(new SimpleGrantedAuthority("role_user")),
                user.isAccountNonExpired(),
                user.isAccountNonLocked(),
                user.isCredentialsNonExpired(),
                user.isEnabled()
        );

        int securityVersion = user.getSecurityVersion() != null ? user.getSecurityVersion() : 0;
        String jwt = jwtUtil.generateTokenWithTenantId(
                userDetails,
                user.getPid(),
                tenantId,
                tenantMember.getId(),
                securityVersion
        );

        // Register server-side session so the JWT passes session validation
        try {
            sessionManagementService.createSession(user.getId(), jwt, "test-seed", "TestSeedController");
        } catch (Exception e) {
            log.warn("Failed to create session for test user: {}", e.getMessage());
        }

        return jwt;
    }
}
