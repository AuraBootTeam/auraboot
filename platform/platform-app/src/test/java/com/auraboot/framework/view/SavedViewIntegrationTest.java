package com.auraboot.framework.view;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.view.dto.SavedViewCreateRequest;
import com.auraboot.framework.view.dto.SavedViewDTO;
import com.auraboot.framework.view.dto.SavedViewUpdateRequest;
import com.auraboot.framework.view.service.SavedViewService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for SavedViewService covering basic CRUD and view management operations.
 * Uses NOT_SUPPORTED propagation so data persists between ordered tests.
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("SavedView CRUD Integration Tests (SV-01~SV-11)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SavedViewIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SavedViewService savedViewService;

    private final String runId = "sv-" + System.currentTimeMillis();
    private final String testModelCode = "test_sv_model_" + System.currentTimeMillis();

    // Cross-test state
    private String viewPid;
    private String defaultViewPid;

    // ==================== SV-01 ====================

    @Test
    @Order(1)
    @DisplayName("SV-01: create PERSONAL view persists correctly")
    void sv01_createPersonalView() {
        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName(runId + "-personal-view");
        request.setModelCode(testModelCode);
        request.setScope("personal");
        request.setViewType("table");

        SavedViewDTO result = savedViewService.create(request);

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isNotBlank();
        assertThat(result.getName()).isEqualTo(runId + "-personal-view");
        assertThat(result.getModelCode()).isEqualTo(testModelCode);
        assertThat(result.getScope()).isEqualTo("personal");

        viewPid = result.getPid();
        log.info("SV-01: created PERSONAL view pid={}", viewPid);
    }

    // ==================== SV-02 ====================

    @Test
    @Order(2)
    @DisplayName("SV-02: findByPid returns the saved view")
    void sv02_findByPid() {
        assertThat(viewPid).as("viewPid must be set by SV-01").isNotBlank();

        SavedViewDTO result = savedViewService.findByPid(viewPid);

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isEqualTo(viewPid);
        assertThat(result.getModelCode()).isEqualTo(testModelCode);

        log.info("SV-02: findByPid returned name={}", result.getName());
    }

    // ==================== SV-03 ====================

    @Test
    @Order(3)
    @DisplayName("SV-03: getPersonalViews includes created view")
    void sv03_getPersonalViewsIncludesCreatedView() {
        assertThat(viewPid).as("viewPid must be set by SV-01").isNotBlank();

        List<SavedViewDTO> views = savedViewService.getPersonalViews(testModelCode, null);

        assertThat(views).isNotNull();
        assertThat(views).extracting(SavedViewDTO::getPid).contains(viewPid);

        log.info("SV-03: getPersonalViews returned {} views", views.size());
    }

    // ==================== SV-04 ====================

    @Test
    @Order(4)
    @DisplayName("SV-04: getAccessibleViews includes PERSONAL views for current user")
    void sv04_getAccessibleViewsIncludesPersonalViews() {
        assertThat(viewPid).as("viewPid must be set by SV-01").isNotBlank();

        List<SavedViewDTO> views = savedViewService.getAccessibleViews(testModelCode, null);

        assertThat(views).isNotNull();
        assertThat(views).extracting(SavedViewDTO::getPid).contains(viewPid);

        log.info("SV-04: getAccessibleViews returned {} views", views.size());
    }

    // ==================== SV-05 ====================

    @Test
    @Order(5)
    @DisplayName("SV-05: update view name")
    void sv05_updateViewName() {
        assertThat(viewPid).as("viewPid must be set by SV-01").isNotBlank();

        SavedViewUpdateRequest updateRequest = new SavedViewUpdateRequest();
        updateRequest.setName(runId + "-updated-name");

        SavedViewDTO result = savedViewService.update(viewPid, updateRequest);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo(runId + "-updated-name");

        log.info("SV-05: updated view name to '{}'", result.getName());
    }

    // ==================== SV-06 ====================

    @Test
    @Order(6)
    @DisplayName("SV-06: setAsDefault marks this view as default")
    void sv06_setAsDefault() {
        assertThat(viewPid).as("viewPid must be set by SV-01").isNotBlank();

        SavedViewDTO result = savedViewService.setAsDefault(viewPid);

        assertThat(result).isNotNull();
        assertThat(result.getIsDefault()).isTrue();

        defaultViewPid = viewPid;
        log.info("SV-06: setAsDefault pid={} isDefault={}", result.getPid(), result.getIsDefault());
    }

    // ==================== SV-07 ====================

    @Test
    @Order(7)
    @DisplayName("SV-07: getDefaultView returns the view set as default")
    void sv07_getDefaultViewReturnsCorrectView() {
        assertThat(defaultViewPid).as("defaultViewPid must be set by SV-06").isNotBlank();

        SavedViewDTO result = savedViewService.getDefaultView(testModelCode, null);

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isEqualTo(defaultViewPid);

        log.info("SV-07: getDefaultView returned pid={}", result.getPid());
    }

    // ==================== SV-08 ====================

    @Test
    @Order(8)
    @DisplayName("SV-08: duplicate creates copy with new pid")
    void sv08_duplicateCreatesNewCopy() {
        assertThat(viewPid).as("viewPid must be set by SV-01").isNotBlank();

        String duplicateName = runId + "-duplicate";
        SavedViewDTO result = savedViewService.duplicate(viewPid, duplicateName);

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isNotBlank();
        assertThat(result.getPid()).isNotEqualTo(viewPid);
        assertThat(result.getModelCode()).isEqualTo(testModelCode);
        assertThat(result.getName()).isEqualTo(duplicateName);

        log.info("SV-08: duplicate created pid={}, name={}", result.getPid(), result.getName());
    }

    // ==================== SV-09 ====================

    @Test
    @Order(9)
    @DisplayName("SV-09: create GLOBAL view visible via getGlobalViews")
    void sv09_createGlobalViewVisibleViaGetGlobalViews() {
        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName(runId + "-global-view");
        request.setModelCode(testModelCode);
        request.setScope("global");
        request.setViewType("table");

        SavedViewDTO created = savedViewService.create(request);
        assertThat(created).isNotNull();
        assertThat(created.getPid()).isNotBlank();
        assertThat(created.getScope()).isEqualTo("global");

        List<SavedViewDTO> globalViews = savedViewService.getGlobalViews(testModelCode, null);

        assertThat(globalViews).isNotNull();
        assertThat(globalViews).extracting(SavedViewDTO::getPid).contains(created.getPid());

        log.info("SV-09: GLOBAL view pid={} found in getGlobalViews", created.getPid());
    }

    // ==================== SV-10 ====================

    @Test
    @Order(10)
    @DisplayName("SV-10: isNameUnique returns false for existing name")
    void sv10_isNameUniqueReturnsFalseForExistingName() {
        assertThat(viewPid).as("viewPid must be set by SV-01").isNotBlank();

        // Dynamically fetch the current name to avoid coupling with SV-05's update string
        String existingName = savedViewService.findByPid(viewPid).getName();

        boolean unique = savedViewService.isNameUnique(testModelCode, null, existingName, null);

        assertThat(unique).isFalse();

        log.info("SV-10: isNameUnique('{}') returned false as expected", existingName);
    }

    // ==================== SV-11 ====================

    @Test
    @Order(11)
    @DisplayName("SV-11: delete view removes it from getPersonalViews")
    void sv11_deleteViewRemovesFromPersonalViews() {
        assertThat(viewPid).as("viewPid must be set by SV-01").isNotBlank();

        savedViewService.delete(viewPid);

        List<SavedViewDTO> views = savedViewService.getPersonalViews(testModelCode, null);

        assertThat(views).extracting(SavedViewDTO::getPid).doesNotContain(viewPid);

        log.info("SV-11: deleted view pid={}, no longer in getPersonalViews", viewPid);
    }
}
