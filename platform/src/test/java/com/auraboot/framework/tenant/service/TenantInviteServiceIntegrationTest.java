package com.auraboot.framework.tenant.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.tenant.dao.entity.Invitation;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

/**
 * TenantInviteService integration tests.
 *
 * <p>Covers:
 * <ul>
 *   <li>I1-01: generateInviteCode creates a valid code</li>
 *   <li>I1-02: getCurrentValidInviteCode returns the generated code</li>
 *   <li>I1-03: validateInviteCode returns true for valid code</li>
 *   <li>I1-04: validateInviteCode returns false for invalid code</li>
 *   <li>I1-05: revokeInviteCode invalidates the code</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantInviteServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TenantInviteService tenantInviteService;

    private String generatedCode;

    // ==================== I1-01: generate ====================

    @Test
    @Order(1)
    @DisplayName("I1-01: generateInviteCode creates a non-blank code")
    void generateInviteCode_createsNonBlankCode() {
        generatedCode = tenantInviteService.generateInviteCode(getTestUser().getId(), 7);

        assertThat(generatedCode).isNotBlank();
        log.info("I1-01: generated invite code={}", generatedCode);
    }

    @Test
    @Order(2)
    @DisplayName("I1-02: getCurrentValidInviteCode returns the generated invite")
    void getCurrentValidInviteCode_returnsValidInvite() {
        assertThat(generatedCode).as("generatedCode must be set by I1-01").isNotBlank();

        Invitation invite = tenantInviteService.getCurrentValidInviteCode(getTestUser().getId());

        assertThat(invite).isNotNull();
        assertThat(invite.getInviterUserId()).isEqualTo(getTestUser().getId());
    }

    @Test
    @Order(3)
    @DisplayName("I1-03: validateInviteCode returns true for the generated code")
    void validateInviteCode_validCode_returnsTrue() {
        assertThat(generatedCode).as("generatedCode must be set by I1-01").isNotBlank();

        boolean valid = tenantInviteService.validateInviteCode(generatedCode);

        assertThat(valid).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("I1-04: validateInviteCode returns false for nonexistent code")
    void validateInviteCode_invalidCode_returnsFalse() {
        boolean valid = tenantInviteService.validateInviteCode("INVALID-CODE-XYZ-9999");

        assertThat(valid).isFalse();
    }

    @Test
    @Order(5)
    @DisplayName("I1-05: revokeInviteCode invalidates the code")
    void revokeInviteCode_invalidatesCode() {
        assertThat(generatedCode).as("generatedCode must be set by I1-01").isNotBlank();

        boolean revoked = tenantInviteService.revokeInviteCode(getTestUser().getId(), generatedCode);

        assertThat(revoked).isTrue();
        boolean stillValid = tenantInviteService.validateInviteCode(generatedCode);
        assertThat(stillValid).isFalse();
    }
}
