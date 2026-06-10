package com.auraboot.framework.integration.tenant;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.RegisterRequest;
import com.auraboot.framework.auth.service.AuthService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.auraboot.framework.saas.constant.SystemMode;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dto.TenantSelectionRequest;
import com.auraboot.framework.tenant.dto.TenantSelectionResponse;
import com.auraboot.framework.tenant.service.TenantApplicationService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M-Deploy verify-don't-trust: the MULTI-mode self-service registration chain
 * (gap Seam 3). The components were ~70% built but never run end-to-end; this IT
 * exercises the real chain on a real stack in MULTI mode.
 *
 * <p><b>Verified real flow (two steps, not auto-tenant-on-register):</b>
 * <ol>
 *   <li>{@code AuthService.register} creates a user + a tenant-less JWT — the
 *       single-tenant register gate is released in MULTI mode.</li>
 *   <li>{@code TenantApplicationService.createTenantForUser} (the second
 *       self-serve step, invoked by TenantSelectionController in production)
 *       creates the tenant, adds the user as a member, runs
 *       {@code bootstrapTenant} (RBAC) and {@code importForTenant} (built-in
 *       plugins), and issues a new JWT carrying the tenantId. Any failure in
 *       that orchestration throws, so a non-null {@code tenantId}+{@code jwt}
 *       response proves the whole chain ran.</li>
 * </ol>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiTenantSelfRegistrationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private TenantApplicationService tenantApplicationService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private SystemModeService systemModeService;

    @Autowired
    private UserService userService;

    @Autowired
    private TenantService tenantService;

    @BeforeAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void enableMultiMode() {
        super.setupTenantContext();
        // Flip the system into MULTI so self-registration is released (default is SINGLE).
        // SYSTEM_MODE is readonly once set, so use initialize() (upsert-style) not set().
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_MODE, SystemMode.MULTI.getCode(),
                "system", "string", "System mode (single/multi/hybrid)", true);
        // The self-serve register/create-tenant flow runs without an ambient tenant context.
        MetaContext.clear();
    }

    @AfterAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void restoreSingleMode() {
        try {
            systemConfigService.initialize(SystemConfigKeys.SYSTEM_MODE, SystemMode.SINGLE.getCode(),
                    "system", "string", "System mode (single/multi/hybrid)", true);
        } catch (Exception ignored) {
            // best-effort restore on the throwaway test DB
        }
    }

    @Test
    void multiMode_selfRegistration_thenCreateTenant_bootstrapsAndIssuesScopedJwt() {
        // The MULTI mode releases the single-tenant self-registration gate.
        assertFalse(systemModeService.isSingleTenant(), "MULTI must release the single-tenant register gate");
        assertTrue(systemModeService.isMultiTenant(), "mode must report multi-tenant");

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
        String email = "selfreg_" + suffix + "@example.com";

        // Step 1 — self-registration creates a user + a tenant-less JWT.
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail(email);
        reg.setPassword("Passw0rd!" + suffix);
        reg.setDisplayName("Self Reg " + suffix);
        AuthenticationResponse authResp = authService.register(reg);
        assertNotNull(authResp.getUserId(), "register must create a user");
        assertNotNull(authResp.getJwt(), "register must issue a (tenant-less) JWT");

        User user = userService.findByEmail(email);
        assertNotNull(user, "the registered user must be persisted");

        // Step 2 — the user self-serves a new tenant: create tenant + bootstrap RBAC +
        // import built-in plugins + issue a tenant-scoped JWT. Any failure throws.
        TenantSelectionRequest sel = new TenantSelectionRequest();
        sel.setAction("create");
        sel.setTenantName("selfreg_org_" + suffix);
        sel.setDisplayName("Self Reg Org " + suffix);
        sel.setContactEmail(email);
        TenantSelectionResponse tResp = tenantApplicationService.createTenantForUser(sel, user);

        assertNotNull(tResp.getTenantId(), "self-serve tenant creation must yield a tenantId (chain ran end-to-end)");
        assertNotNull(tResp.getJwt(), "must issue a new JWT carrying the tenant");
        assertNotEquals(authResp.getJwt(), tResp.getJwt(),
                "the tenant-scoped JWT must differ from the tenant-less register JWT");

        Tenant created = tenantService.findByName(sel.getTenantName());
        assertNotNull(created, "the created tenant must be persisted");
        assertEquals(tResp.getTenantId(), created.getId(), "response tenantId must match the persisted tenant");
    }
}
