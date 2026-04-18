package com.auraboot.framework.user.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.exception.UserException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * UserService.searchInTenant integration tests.
 *
 * <p>Covers the backend of {@code GET /api/admin/users/search}:
 * <ul>
 *   <li>US-01: returns tenant members matching by nick_name</li>
 *   <li>US-02: returns tenant members matching by email</li>
 *   <li>US-03: blank keyword returns all tenant members (up to size)</li>
 *   <li>US-04: tenant isolation — users from another tenant are excluded</li>
 *   <li>US-05: size clamped to [1, 200]</li>
 *   <li>US-06: null tenantId throws NPE (guard for controller to translate)</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserSearchIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantMemberService tenantMemberService;

    private String uniquePrefix;
    private User userA;
    private User userB;
    private Tenant otherTenant;
    private User otherTenantUser;
    private boolean seeded = false;

    @BeforeEach
    void seedUsers() throws UserException {
        if (seeded) {
            return;
        }
        seeded = true;
        // Prefix keeps search keywords unique across reruns
        uniquePrefix = "pick" + UniqueIdGenerator.generate().toLowerCase().substring(0, 10);

        // Two users in the BaseIntegrationTest testTenant
        userA = userService.signUp(uniquePrefix + "-a@test.local", "Test2026x!", uniquePrefix + "-alice");
        tenantMemberService.addMember(userA.getId(), getTestTenant().getId(), "active");

        userB = userService.signUp(uniquePrefix + "-b@test.local", "Test2026x!", uniquePrefix + "-bob");
        tenantMemberService.addMember(userB.getId(), getTestTenant().getId(), "active");

        // Separate tenant + user to prove isolation
        Tenant other = new Tenant();
        other.setPid(UniqueIdGenerator.generate());
        other.setName(uniquePrefix + "-other-tenant");
        other.setDisplayName("Isolation Tenant " + uniquePrefix);
        other.setStatus("active");
        other.setContactEmail("admin@" + uniquePrefix + "-other.local");
        other.setDescription("Tenant isolation fixture");
        other.setDeletedFlag(false);
        other.setCreatedAt(Instant.now());
        other.setUpdatedAt(Instant.now());
        otherTenant = tenantService.createTenant(other);

        otherTenantUser = userService.signUp(uniquePrefix + "-isolated@test.local", "Test2026x!", uniquePrefix + "-alice-isolated");
        tenantMemberService.addMember(otherTenantUser.getId(), otherTenant.getId(), "active");
    }

    @Test
    @Order(1)
    @DisplayName("US-01: matches by display name (nick_name)")
    void search_matchesByNickName() {
        List<UserSearchDTO> results = userService.searchInTenant(getTestTenant().getId(), uniquePrefix + "-ali", 20);

        // userA nick is "<prefix>-alice" — should match, and must NOT leak otherTenantUser (also alice-isolated)
        assertThat(results)
                .extracting(UserSearchDTO::getPid)
                .contains(userA.getPid())
                .doesNotContain(otherTenantUser.getPid());
    }

    @Test
    @Order(2)
    @DisplayName("US-02: matches by email")
    void search_matchesByEmail() {
        List<UserSearchDTO> results = userService.searchInTenant(getTestTenant().getId(), uniquePrefix + "-b@", 20);

        assertThat(results)
                .extracting(UserSearchDTO::getPid)
                .contains(userB.getPid())
                .doesNotContain(userA.getPid(), otherTenantUser.getPid());
    }

    @Test
    @Order(3)
    @DisplayName("US-03: blank keyword returns tenant members")
    void search_blankKeywordReturnsAll() {
        List<UserSearchDTO> results = userService.searchInTenant(getTestTenant().getId(), "", 50);

        // At least our two seeded users should be present
        assertThat(results)
                .extracting(UserSearchDTO::getPid)
                .contains(userA.getPid(), userB.getPid())
                .doesNotContain(otherTenantUser.getPid());

        // Returned DTO must not expose any password-ish field — UserSearchDTO has no such property,
        // so we just assert the shape we expect.
        assertThat(results.get(0).getDisplayName()).isNotBlank();
    }

    @Test
    @Order(4)
    @DisplayName("US-04: tenant isolation — other tenant users never returned")
    void search_tenantIsolation() {
        // Search from other tenant's perspective: our seeded userA/userB must not appear
        List<UserSearchDTO> results = userService.searchInTenant(otherTenant.getId(), uniquePrefix, 50);

        assertThat(results)
                .extracting(UserSearchDTO::getPid)
                .contains(otherTenantUser.getPid())
                .doesNotContain(userA.getPid(), userB.getPid());
    }

    @Test
    @Order(5)
    @DisplayName("US-05: size is clamped — negative/zero → 1, huge → 200")
    void search_sizeClamp() {
        List<UserSearchDTO> resultsZero = userService.searchInTenant(getTestTenant().getId(), "", 0);
        assertThat(resultsZero.size()).isLessThanOrEqualTo(1);

        List<UserSearchDTO> resultsHuge = userService.searchInTenant(getTestTenant().getId(), "", 10_000);
        assertThat(resultsHuge.size()).isLessThanOrEqualTo(200);
    }

    @Test
    @Order(6)
    @DisplayName("US-06: null tenantId is rejected (controller translates to 400)")
    void search_nullTenantRejected() {
        assertThatThrownBy(() -> userService.searchInTenant(null, "x", 20))
                .isInstanceOf(NullPointerException.class);
    }
}
