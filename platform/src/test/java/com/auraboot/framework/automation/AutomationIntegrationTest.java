package com.auraboot.framework.automation;

import com.auraboot.framework.automation.dto.AutomationCreateRequest;
import com.auraboot.framework.automation.dto.AutomationDTO;
import com.auraboot.framework.automation.dto.AutomationUpdateRequest;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Automation CRUD integration tests (AT-01 through AT-10).
 * Tests persist to real DB (NOT_SUPPORTED propagation, no rollback).
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("Automation Integration Tests (AT-01~AT-10)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AutomationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AutomationService automationService;

    private final String runId = "AT-" + System.currentTimeMillis();
    private final String modelCode = "at-model-" + runId;

    /** Shared state across ordered tests */
    private String automationPid;

    // ==================== Helper ====================

    private AutomationCreateRequest buildRequest(String name) {
        AutomationCreateRequest req = new AutomationCreateRequest();
        req.setName(name);
        req.setModelCode(modelCode);
        req.setTriggerType("on_record_create");
        req.setActions(List.of());
        req.setFlowConfig(Map.of("type", "simple"));
        req.setEnabled(false);
        return req;
    }

    // ==================== AT-01: create persists ====================

    @Test
    @Order(1)
    @DisplayName("AT-01: create automation persists with correct fields")
    void AT01_create_automationPersists() {
        AutomationCreateRequest req = buildRequest("AT-01-auto-" + runId);

        AutomationDTO dto = automationService.create(req);

        assertThat(dto).isNotNull();
        assertThat(dto.getPid()).isNotBlank();
        assertThat(dto.getName()).isEqualTo("AT-01-auto-" + runId);
        assertThat(dto.getTriggerType()).isEqualTo("on_record_create");
        assertThat(dto.getEnabled()).isFalse();

        automationPid = dto.getPid();
        log.info("AT-01 created automationPid={}", automationPid);
    }

    // ==================== AT-02: findByPid returns automation ====================

    @Test
    @Order(2)
    @DisplayName("AT-02: findByPid returns the automation")
    void AT02_findByPid_returnsAutomation() {
        assertThat(automationPid).as("automationPid must be set by AT-01").isNotNull();

        AutomationDTO dto = automationService.findByPid(automationPid);

        assertThat(dto).isNotNull();
        assertThat(dto.getPid()).isEqualTo(automationPid);
        assertThat(dto.getModelCode()).isEqualTo(modelCode);
    }

    // ==================== AT-03: getByModelCode includes created ====================

    @Test
    @Order(3)
    @DisplayName("AT-03: getByModelCode returns automations for the model (includes created)")
    void AT03_getByModelCode_includesCreated() {
        List<AutomationDTO> list = automationService.getByModelCode(modelCode);

        assertThat(list).isNotNull();
        assertThat(list).isNotEmpty();
        assertThat(list).anyMatch(a -> automationPid.equals(a.getPid()));
        assertThat(list).allMatch(a -> modelCode.equals(a.getModelCode()));
    }

    // ==================== AT-04: enable sets enabled=true ====================

    @Test
    @Order(4)
    @DisplayName("AT-04: enable sets enabled=true")
    void AT04_enable_setsEnabledTrue() {
        AutomationDTO enabled = automationService.enable(automationPid);

        assertThat(enabled).isNotNull();
        assertThat(enabled.getEnabled()).isTrue();
    }

    // ==================== AT-05: getEnabledByModelCode returns only enabled ====================

    @Test
    @Order(5)
    @DisplayName("AT-05: getEnabledByModelCode returns only enabled automations (all enabled=true)")
    void AT05_getEnabledByModelCode_returnsOnlyEnabled() {
        List<AutomationDTO> enabledList = automationService.getEnabledByModelCode(modelCode);

        assertThat(enabledList).isNotNull();
        assertThat(enabledList).isNotEmpty();
        assertThat(enabledList).anyMatch(a -> automationPid.equals(a.getPid()));
        assertThat(enabledList).allMatch(a -> Boolean.TRUE.equals(a.getEnabled()));
    }

    // ==================== AT-06: disable sets enabled=false ====================

    @Test
    @Order(6)
    @DisplayName("AT-06: disable sets enabled=false")
    void AT06_disable_setsEnabledFalse() {
        AutomationDTO disabled = automationService.disable(automationPid);

        assertThat(disabled).isNotNull();
        assertThat(disabled.getEnabled()).isFalse();
    }

    // ==================== AT-07: update changes name/description ====================

    @Test
    @Order(7)
    @DisplayName("AT-07: update changes name/description")
    void AT07_update_changesNameAndDescription() {
        AutomationUpdateRequest req = new AutomationUpdateRequest();
        req.setName("AT-07-updated-" + runId);
        req.setDescription("Updated description for AT-07-" + runId);

        AutomationDTO updated = automationService.update(automationPid, req);

        assertThat(updated).isNotNull();
        assertThat(updated.getName()).isEqualTo("AT-07-updated-" + runId);
        assertThat(updated.getDescription()).isEqualTo("Updated description for AT-07-" + runId);
    }

    // ==================== AT-08: duplicate creates copy with new pid ====================

    @Test
    @Order(8)
    @DisplayName("AT-08: duplicate creates copy with new pid, same triggerType")
    void AT08_duplicate_createsCopyWithNewPid() {
        AutomationDTO copy = automationService.duplicate(automationPid);

        assertThat(copy).isNotNull();
        assertThat(copy.getPid()).isNotBlank();
        assertThat(copy.getPid()).isNotEqualTo(automationPid);
        assertThat(copy.getTriggerType()).isEqualTo("on_record_create");
    }

    // ==================== AT-09: search by keyword returns matching results ====================

    @Test
    @Order(9)
    @DisplayName("AT-09: search by keyword returns matching results (getTotal()>0)")
    void AT09_search_byKeyword_returnsMatchingResults() {
        // Create a uniquely named automation to search for
        String uniqueName = "AT-09-search-" + runId;
        automationService.create(buildRequest(uniqueName));

        PageResult<AutomationDTO> result = automationService.search(uniqueName, null, null, null, 1, 10);

        assertThat(result).isNotNull();
        assertThat(result.getTotal()).isGreaterThan(0);
    }

    // ==================== AT-10: delete soft-deletes, not visible in getByModelCode ====================

    @Test
    @Order(10)
    @DisplayName("AT-10: delete soft-deletes, not visible in getByModelCode")
    void AT10_delete_notVisibleInGetByModelCode() {
        // Create a separate automation to delete (avoid impacting automationPid)
        AutomationDTO toDelete = automationService.create(buildRequest("AT-10-delete-" + runId));
        String deletePid = toDelete.getPid();

        automationService.delete(deletePid);

        // Verify it is no longer returned by getByModelCode
        List<AutomationDTO> list = automationService.getByModelCode(modelCode);
        assertThat(list).noneMatch(a -> deletePid.equals(a.getPid()));

        // Also confirm findByPid returns null after deletion
        AutomationDTO found = automationService.findByPid(deletePid);
        assertThat(found).isNull();
    }
}
