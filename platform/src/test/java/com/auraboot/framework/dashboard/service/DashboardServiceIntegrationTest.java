package com.auraboot.framework.dashboard.service;

import com.auraboot.framework.dashboard.dto.*;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for DashboardService.
 *
 * <p>Tests CRUD lifecycle, access control queries, publish/unpublish,
 * default dashboard, and duplicate operations.
 *
 * <ul>
 *   <li>DB-01: create PERSONAL dashboard persists with correct fields</li>
 *   <li>DB-02: findByPid returns the created dashboard</li>
 *   <li>DB-03: findByCode returns the created dashboard</li>
 *   <li>DB-04: update changes title and description</li>
 *   <li>DB-05: isCodeUnique returns false for existing code</li>
 *   <li>DB-06: publish transitions status to PUBLISHED</li>
 *   <li>DB-07: unpublish transitions status back to DRAFT</li>
 *   <li>DB-08: setAsDefault marks the dashboard as default</li>
 *   <li>DB-09: getPersonalDashboards includes the created dashboard</li>
 *   <li>DB-10: duplicate creates a new dashboard with different code and pid</li>
 *   <li>DB-11: delete soft-deletes the dashboard</li>
 *   <li>DB-12: create with duplicate code throws ValidationException</li>
 * </ul>
 */
@Slf4j
@DisplayName("DashboardService Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DashboardServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DashboardService dashboardService;

    private final String runId = "db-" + System.currentTimeMillis();

    // Shared state across ordered tests
    private String dashboardPid;
    private String dashboardCode;

    // ==================== DB-01: create PERSONAL dashboard ====================

    @Test
    @Order(1)
    @DisplayName("DB-01: create PERSONAL dashboard persists with correct fields")
    void DB_01_create_personalDashboard_persistsCorrectFields() {
        dashboardCode = "test-dash-" + runId;

        DashboardCreateRequest request = new DashboardCreateRequest();
        request.setCode(dashboardCode);
        request.setTitle("Test Dashboard " + runId);
        request.setDescription("Integration test dashboard for " + runId);
        request.setScope("personal");
        request.setIsDefault(false);
        request.setSortOrder(1);

        DashboardDTO result = dashboardService.create(request);

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isNotBlank();
        assertThat(result.getCode()).isEqualTo(dashboardCode);
        assertThat(result.getTitle()).isEqualTo("Test Dashboard " + runId);
        assertThat(result.getDescription()).isEqualTo("Integration test dashboard for " + runId);
        assertThat(result.getScope()).isEqualTo("personal");
        assertThat(result.getStatus()).isEqualTo("draft");
        assertThat(result.getIsDefault()).isFalse();

        this.dashboardPid = result.getPid();
        log.info("DB-01: created dashboard pid={}", dashboardPid);
    }

    // ==================== DB-02: findByPid ====================

    @Test
    @Order(2)
    @DisplayName("DB-02: findByPid returns the dashboard created in DB-01")
    void DB_02_findByPid_returnsCreatedDashboard() {
        assertThat(dashboardPid).as("dashboardPid from DB-01").isNotBlank();

        DashboardDTO found = dashboardService.findByPid(dashboardPid);

        assertThat(found).isNotNull();
        assertThat(found.getPid()).isEqualTo(dashboardPid);
        assertThat(found.getCode()).isEqualTo(dashboardCode);
    }

    // ==================== DB-03: findByCode ====================

    @Test
    @Order(3)
    @DisplayName("DB-03: findByCode returns the dashboard created in DB-01")
    void DB_03_findByCode_returnsCreatedDashboard() {
        assertThat(dashboardCode).as("dashboardCode from DB-01").isNotBlank();

        DashboardDTO found = dashboardService.findByCode(dashboardCode);

        assertThat(found).isNotNull();
        assertThat(found.getPid()).isEqualTo(dashboardPid);
        assertThat(found.getTitle()).isEqualTo("Test Dashboard " + runId);
    }

    // ==================== DB-04: update changes title ====================

    @Test
    @Order(4)
    @DisplayName("DB-04: update changes title and description")
    void DB_04_update_changesTitleAndDescription() {
        assertThat(dashboardPid).as("dashboardPid from DB-01").isNotBlank();

        String newTitle = "Updated Dashboard " + runId;
        DashboardUpdateRequest request = new DashboardUpdateRequest();
        request.setTitle(newTitle);
        request.setDescription("Updated description " + runId);

        DashboardDTO updated = dashboardService.update(dashboardPid, request);

        assertThat(updated).isNotNull();
        assertThat(updated.getTitle()).isEqualTo(newTitle);
        assertThat(updated.getDescription()).isEqualTo("Updated description " + runId);

        // Verify via findByPid
        DashboardDTO reloaded = dashboardService.findByPid(dashboardPid);
        assertThat(reloaded.getTitle()).isEqualTo(newTitle);
    }

    // ==================== DB-05: isCodeUnique ====================

    @Test
    @Order(5)
    @DisplayName("DB-05: isCodeUnique returns false for an existing code")
    void DB_05_isCodeUnique_existingCode_returnsFalse() {
        assertThat(dashboardCode).as("dashboardCode from DB-01").isNotBlank();

        boolean unique = dashboardService.isCodeUnique(dashboardCode, null);
        assertThat(unique).isFalse();

        // With the same pid excluded, it should be considered unique
        boolean uniqueExcluded = dashboardService.isCodeUnique(dashboardCode, dashboardPid);
        assertThat(uniqueExcluded).isTrue();

        // A completely new code should be unique
        boolean newCode = dashboardService.isCodeUnique("brand-new-code-" + runId, null);
        assertThat(newCode).isTrue();
    }

    // ==================== DB-06: publish transitions to PUBLISHED ====================

    @Test
    @Order(6)
    @DisplayName("DB-06: publish transitions dashboard status to PUBLISHED")
    void DB_06_publish_transitionsStatusToPublished() {
        assertThat(dashboardPid).as("dashboardPid from DB-01").isNotBlank();

        // Add a minimal widget so publish validation passes
        DashboardUpdateRequest updateReq = new DashboardUpdateRequest();
        com.fasterxml.jackson.databind.node.ObjectNode widget =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        widget.put("id", "w1").put("type", "stat-card");
        com.fasterxml.jackson.databind.node.ObjectNode cfg =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        cfg.put("title", "Test Widget");
        widget.set("config", cfg);
        updateReq.setWidgets(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(widget));
        dashboardService.update(dashboardPid, updateReq);

        DashboardDTO published = dashboardService.publish(dashboardPid);

        assertThat(published).isNotNull();
        assertThat(published.getStatus()).isEqualTo("published");
    }

    // ==================== DB-07: unpublish transitions back to DRAFT ====================

    @Test
    @Order(7)
    @DisplayName("DB-07: unpublish transitions dashboard status back to DRAFT")
    void DB_07_unpublish_transitionsBackToDraft() {
        assertThat(dashboardPid).as("dashboardPid from DB-01").isNotBlank();

        DashboardDTO unpublished = dashboardService.unpublish(dashboardPid);

        assertThat(unpublished).isNotNull();
        assertThat(unpublished.getStatus()).isEqualTo("draft");
    }

    // ==================== DB-08: setAsDefault ====================

    @Test
    @Order(8)
    @DisplayName("DB-08: setAsDefault marks the dashboard as the user's default")
    void DB_08_setAsDefault_marksAsDefault() {
        assertThat(dashboardPid).as("dashboardPid from DB-01").isNotBlank();

        DashboardDTO defaultDash = dashboardService.setAsDefault(dashboardPid);

        assertThat(defaultDash).isNotNull();
        assertThat(defaultDash.getIsDefault()).isTrue();
    }

    // ==================== DB-09: getPersonalDashboards includes created ====================

    @Test
    @Order(9)
    @DisplayName("DB-09: getPersonalDashboards includes the dashboard created in DB-01")
    void DB_09_getPersonalDashboards_includesCreatedDashboard() {
        assertThat(dashboardPid).as("dashboardPid from DB-01").isNotBlank();

        List<DashboardDTO> personal = dashboardService.getPersonalDashboards();

        assertThat(personal).isNotNull().isNotEmpty();
        assertThat(personal).anyMatch(d -> d.getPid().equals(dashboardPid));
    }

    // ==================== DB-10: duplicate creates new dashboard ====================

    @Test
    @Order(10)
    @DisplayName("DB-10: duplicate creates a new dashboard with a different pid and code")
    void DB_10_duplicate_createsNewDashboard() {
        assertThat(dashboardPid).as("dashboardPid from DB-01").isNotBlank();

        String duplicateTitle = "Duplicate of " + runId;
        DashboardDTO duplicate = dashboardService.duplicate(dashboardPid, duplicateTitle);

        assertThat(duplicate).isNotNull();
        assertThat(duplicate.getPid()).isNotEqualTo(dashboardPid);
        assertThat(duplicate.getCode()).isNotEqualTo(dashboardCode);
        assertThat(duplicate.getTitle()).isEqualTo(duplicateTitle);
        assertThat(duplicate.getStatus()).isEqualTo("draft");

        log.info("DB-10: duplicate pid={}, code={}", duplicate.getPid(), duplicate.getCode());
    }

    // ==================== DB-11: delete soft-deletes the dashboard ====================

    @Test
    @Order(11)
    @DisplayName("DB-11: delete soft-deletes the dashboard so findByPid returns null")
    void DB_11_delete_softDeletesDashboard() {
        assertThat(dashboardPid).as("dashboardPid from DB-01").isNotBlank();

        dashboardService.delete(dashboardPid);

        DashboardDTO afterDelete = dashboardService.findByPid(dashboardPid);
        assertThat(afterDelete).isNull();
    }

    // ==================== DB-12: create with duplicate code throws ====================

    @Test
    @Order(12)
    @DisplayName("DB-12: create with duplicate code throws ValidationException")
    void DB_12_create_duplicateCode_throwsValidationException() {
        // First create a dashboard
        String code = "dup-code-" + runId;
        DashboardCreateRequest first = new DashboardCreateRequest();
        first.setCode(code);
        first.setTitle("First " + runId);
        first.setScope("personal");
        dashboardService.create(first);

        // Second create with the same code should throw
        DashboardCreateRequest second = new DashboardCreateRequest();
        second.setCode(code);
        second.setTitle("Second " + runId);
        second.setScope("personal");

        assertThatThrownBy(() -> dashboardService.create(second))
                .hasMessageContaining(code);
    }
}
