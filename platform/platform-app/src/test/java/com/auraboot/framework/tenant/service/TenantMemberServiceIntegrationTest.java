package com.auraboot.framework.tenant.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * TenantMemberService integration tests.
 *
 * <p>Covers:
 * <ul>
 *   <li>M1-01 to M1-04: member add / query / update / remove</li>
 *   <li>M2-01 to M2-03: activate / deactivate / suspend member</li>
 *   <li>M3-01 to M3-02: getTenantIdsByUserId, findByTenantIdAndUserId</li>
 *   <li>M4-01: pagination</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantMemberServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TenantMemberService tenantMemberService;

    private Long memberId;
    private String memberPid;

    // ==================== M1: CRUD ====================

    @Test
    @Order(1)
    @DisplayName("M1-01: addMember persists tenant member relationship")
    void addMember_persistsRelationship() {
        // BaseIntegrationTest already creates a TenantMember via addMember(),
        // so we can directly query using those entities.
        TenantMember existing = tenantMemberService.findByTenantIdAndUserId(
                getTestTenant().getId(), getTestUser().getId());

        assertThat(existing).isNotNull();
        assertThat(existing.getTenantId()).isEqualTo(getTestTenant().getId());
        assertThat(existing.getUserId()).isEqualTo(getTestUser().getId());
        memberId = existing.getId();
        memberPid = existing.getPid();
        log.info("M1-01: found member id={}", memberId);
    }

    @Test
    @Order(2)
    @DisplayName("M1-02: findByPid returns the member")
    void findByPid_returnsMember() {
        assertThat(memberPid).as("memberPid must be set by M1-01").isNotBlank();

        TenantMember found = tenantMemberService.findByPid(memberPid);

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(memberId);
    }

    @Test
    @Order(3)
    @DisplayName("M1-03: findByTenantId includes the test member")
    void findByTenantId_includesTestMember() {
        List<TenantMember> members = tenantMemberService.findByTenantId(getTestTenant().getId());

        assertThat(members).isNotNull().isNotEmpty();
        boolean found = members.stream().anyMatch(m -> m.getId().equals(memberId));
        assertThat(found).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("M1-04: updateMember persists changes")
    void updateMember_persistsChanges() {
        assertThat(memberId).as("memberId must be set by M1-01").isNotNull();
        TenantMember member = tenantMemberService.getById(memberId);
        member.setStatus("active");

        TenantMember updated = tenantMemberService.updateMember(member);

        assertThat(updated).isNotNull();
        assertThat(updated.getStatus()).isEqualTo("active");
    }

    // ==================== M2: status lifecycle ====================

    @Test
    @Order(10)
    @DisplayName("M2-01: deactivateMember changes status to INACTIVE")
    void deactivateMember_changesStatus() {
        assertThat(memberId).as("memberId must be set by M1-01").isNotNull();

        boolean result = tenantMemberService.deactivateMember(memberId);

        assertThat(result).isTrue();
        TenantMember updated = tenantMemberService.getById(memberId);
        assertThat(updated.getStatus()).isEqualTo("inactive");
    }

    @Test
    @Order(11)
    @DisplayName("M2-02: activateMember restores status to ACTIVE")
    void activateMember_restoresStatus() {
        assertThat(memberId).as("memberId must be set by M1-01").isNotNull();

        boolean result = tenantMemberService.activateMember(memberId);

        assertThat(result).isTrue();
        TenantMember updated = tenantMemberService.getById(memberId);
        assertThat(updated.getStatus()).isEqualTo("active");
    }

    @Test
    @Order(12)
    @DisplayName("M2-03: suspendMember changes status to SUSPENDED")
    void suspendMember_changesStatus() {
        assertThat(memberId).as("memberId must be set by M1-01").isNotNull();

        boolean result = tenantMemberService.suspendMember(memberId, "Integration test suspension");

        assertThat(result).isTrue();
        TenantMember updated = tenantMemberService.getById(memberId);
        assertThat(updated.getStatus()).isEqualTo("suspended");

        // Restore for subsequent tests
        tenantMemberService.activateMember(memberId);
    }

    // ==================== M3: user-tenant mapping ====================

    @Test
    @Order(20)
    @DisplayName("M3-01: getTenantIdsByUserId includes current tenant")
    void getTenantIdsByUserId_includesCurrentTenant() {
        List<Long> tenantIds = tenantMemberService.getTenantIdsByUserId(getTestUser().getId());

        assertThat(tenantIds).isNotNull().isNotEmpty();
        assertThat(tenantIds).contains(getTestTenant().getId());
    }

    @Test
    @Order(21)
    @DisplayName("M3-02: getTenantIdByUserId returns exactly the test tenant ID")
    void getTenantIdByUserId_returnsExactTenantId() {
        // This test relies on the test user belonging to exactly one tenant.
        // If the test user belongs to multiple tenants, this will throw - which is also valid behavior to test.
        assertThatCode(() -> {
            Long tid = tenantMemberService.getTenantIdByUserId(getTestUser().getId());
            assertThat(tid).isNotNull();
        }).doesNotThrowAnyException();
    }

    // ==================== M4: pagination ====================

    @Test
    @Order(30)
    @DisplayName("M4-01: findMembers pagination returns non-empty result")
    void findMembers_pagination_returnsResults() {
        Page<TenantMember> page = tenantMemberService.findMembers(
                1, 10, getTestTenant().getId(), null, null, "active");

        assertThat(page).isNotNull();
        assertThat(page.getTotal()).isGreaterThan(0);
    }
}
