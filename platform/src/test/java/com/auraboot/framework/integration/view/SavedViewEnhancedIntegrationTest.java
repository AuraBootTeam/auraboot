package com.auraboot.framework.integration.view;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.view.dto.SavedViewCreateRequest;
import com.auraboot.framework.view.dto.SavedViewDTO;
import com.auraboot.framework.view.entity.ViewConfig;
import com.auraboot.framework.view.service.SavedViewService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for enhanced SavedView features.
 * Tests new view types (CALENDAR, GALLERY, GANTT) and their ViewConfig persistence.
 */
@Slf4j
@DisplayName("SavedView Enhanced Features - Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SavedViewEnhancedIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SavedViewService savedViewService;

    private static final String TEST_MODEL_CODE = "device";

    // ==================== Calendar View ====================

    @Test
    @Order(1)
    @DisplayName("Create CALENDAR view with full config")
    void test01_createCalendarView() {
        ViewConfig config = new ViewConfig();
        config.setCalendarDateField("created_at");
        config.setCalendarTitleField("name");
        config.setCalendarEndDateField("updated_at");
        config.setCalendarColorField("status");
        config.setCalendarDefaultView("dayGridMonth");

        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName("Calendar Test View");
        request.setModelCode(TEST_MODEL_CODE);
        request.setViewType("calendar");
        request.setScope("personal");
        request.setViewConfig(config);

        SavedViewDTO result = savedViewService.create(request);

        assertNotNull(result);
        assertNotNull(result.getPid());
        assertEquals("calendar", result.getViewType());
        assertEquals("Calendar Test View", result.getName());

        // Verify config persisted
        ViewConfig savedConfig = result.getViewConfig();
        assertNotNull(savedConfig);
        assertEquals("created_at", savedConfig.getCalendarDateField());
        assertEquals("name", savedConfig.getCalendarTitleField());
        assertEquals("updated_at", savedConfig.getCalendarEndDateField());
        assertEquals("status", savedConfig.getCalendarColorField());
        assertEquals("dayGridMonth", savedConfig.getCalendarDefaultView());

        log.info("Created CALENDAR view with pid={}", result.getPid());
    }

    @Test
    @Order(2)
    @DisplayName("Create CALENDAR view with minimal config")
    void test02_createCalendarViewMinimal() {
        ViewConfig config = new ViewConfig();
        config.setCalendarDateField("created_at");

        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName("Calendar Minimal");
        request.setModelCode(TEST_MODEL_CODE);
        request.setViewType("calendar");
        request.setScope("personal");
        request.setViewConfig(config);

        SavedViewDTO result = savedViewService.create(request);

        assertNotNull(result);
        assertEquals("calendar", result.getViewType());

        ViewConfig savedConfig = result.getViewConfig();
        assertNotNull(savedConfig);
        assertEquals("created_at", savedConfig.getCalendarDateField());
        assertNull(savedConfig.getCalendarEndDateField());
        assertNull(savedConfig.getCalendarColorField());
    }

    // ==================== Gallery View ====================

    @Test
    @Order(3)
    @DisplayName("Create GALLERY view with full config")
    void test03_createGalleryView() {
        ViewConfig config = new ViewConfig();
        config.setGalleryImageField("image_url");
        config.setGalleryTitleField("name");
        config.setGalleryDescriptionField("description");
        config.setGalleryColumns(4);
        config.setGalleryAspectRatio("16:9");
        config.setGalleryShowTitle(true);
        config.setGalleryShowDescription(true);
        config.setGalleryDisplayFields(List.of("status", "created_at"));

        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName("Gallery Test View");
        request.setModelCode(TEST_MODEL_CODE);
        request.setViewType("gallery");
        request.setScope("personal");
        request.setViewConfig(config);

        SavedViewDTO result = savedViewService.create(request);

        assertNotNull(result);
        assertEquals("gallery", result.getViewType());

        ViewConfig savedConfig = result.getViewConfig();
        assertNotNull(savedConfig);
        assertEquals("image_url", savedConfig.getGalleryImageField());
        assertEquals("name", savedConfig.getGalleryTitleField());
        assertEquals("description", savedConfig.getGalleryDescriptionField());
        assertEquals(4, savedConfig.getGalleryColumns());
        assertEquals("16:9", savedConfig.getGalleryAspectRatio());
        assertTrue(savedConfig.getGalleryShowTitle());
        assertTrue(savedConfig.getGalleryShowDescription());
        assertEquals(2, savedConfig.getGalleryDisplayFields().size());
        assertTrue(savedConfig.getGalleryDisplayFields().contains("status"));

        log.info("Created GALLERY view with pid={}", result.getPid());
    }

    @Test
    @Order(4)
    @DisplayName("Create GALLERY view with default columns")
    void test04_createGalleryViewDefaults() {
        ViewConfig config = new ViewConfig();
        config.setGalleryTitleField("name");

        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName("Gallery Defaults");
        request.setModelCode(TEST_MODEL_CODE);
        request.setViewType("gallery");
        request.setScope("personal");
        request.setViewConfig(config);

        SavedViewDTO result = savedViewService.create(request);

        assertNotNull(result);
        assertEquals("gallery", result.getViewType());

        ViewConfig savedConfig = result.getViewConfig();
        assertNotNull(savedConfig);
        assertEquals("name", savedConfig.getGalleryTitleField());
        // Columns should be null (frontend defaults to 3)
        assertNull(savedConfig.getGalleryColumns());
    }

    // ==================== Gantt View ====================

    @Test
    @Order(5)
    @DisplayName("Create GANTT view with full config")
    void test05_createGanttView() {
        ViewConfig config = new ViewConfig();
        config.setGanttStartDateField("start_date");
        config.setGanttEndDateField("end_date");
        config.setGanttTitleField("task_name");
        config.setGanttProgressField("progress");
        config.setGanttDependencyField("depends_on");
        config.setGanttDefaultView("Week");

        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName("Gantt Test View");
        request.setModelCode(TEST_MODEL_CODE);
        request.setViewType("gantt");
        request.setScope("personal");
        request.setViewConfig(config);

        SavedViewDTO result = savedViewService.create(request);

        assertNotNull(result);
        assertEquals("gantt", result.getViewType());

        ViewConfig savedConfig = result.getViewConfig();
        assertNotNull(savedConfig);
        assertEquals("start_date", savedConfig.getGanttStartDateField());
        assertEquals("end_date", savedConfig.getGanttEndDateField());
        assertEquals("task_name", savedConfig.getGanttTitleField());
        assertEquals("progress", savedConfig.getGanttProgressField());
        assertEquals("depends_on", savedConfig.getGanttDependencyField());
        assertEquals("Week", savedConfig.getGanttDefaultView());

        log.info("Created GANTT view with pid={}", result.getPid());
    }

    // ==================== Read-back and Update ====================

    @Test
    @Order(6)
    @DisplayName("Read back view and verify config preserved")
    void test06_readBackViewConfig() {
        // Create a CALENDAR view
        ViewConfig config = new ViewConfig();
        config.setCalendarDateField("event_date");
        config.setCalendarTitleField("event_name");
        config.setCalendarDefaultView("timeGridWeek");

        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName("Read-back Test");
        request.setModelCode(TEST_MODEL_CODE);
        request.setViewType("calendar");
        request.setScope("personal");
        request.setViewConfig(config);

        SavedViewDTO created = savedViewService.create(request);
        assertNotNull(created.getPid());

        // Read it back
        SavedViewDTO readBack = savedViewService.findByPid(created.getPid());

        assertNotNull(readBack);
        assertEquals(created.getPid(), readBack.getPid());
        assertEquals("calendar", readBack.getViewType());

        ViewConfig readConfig = readBack.getViewConfig();
        assertNotNull(readConfig);
        assertEquals("event_date", readConfig.getCalendarDateField());
        assertEquals("event_name", readConfig.getCalendarTitleField());
        assertEquals("timeGridWeek", readConfig.getCalendarDefaultView());
    }

    @Test
    @Order(7)
    @DisplayName("View with null config should not fail")
    void test07_viewWithNullConfig() {
        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName("No Config View");
        request.setModelCode(TEST_MODEL_CODE);
        request.setViewType("table");
        request.setScope("personal");
        // No viewConfig set

        SavedViewDTO result = savedViewService.create(request);

        assertNotNull(result);
        assertEquals("table", result.getViewType());
    }

    // ==================== Mixed Config ====================

    @Test
    @Order(8)
    @DisplayName("Create view with mixed Kanban + Calendar config")
    void test08_mixedConfig() {
        // A ViewConfig can have fields from multiple view types
        // Only the fields relevant to the view type are used
        ViewConfig config = new ViewConfig();
        config.setCalendarDateField("date_field");
        config.setGanttStartDateField("start_field");
        config.setGalleryTitleField("title_field");

        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName("Mixed Config View");
        request.setModelCode(TEST_MODEL_CODE);
        request.setViewType("calendar");
        request.setScope("personal");
        request.setViewConfig(config);

        SavedViewDTO result = savedViewService.create(request);

        assertNotNull(result);

        // All fields should persist regardless of view type
        ViewConfig savedConfig = result.getViewConfig();
        assertNotNull(savedConfig);
        assertEquals("date_field", savedConfig.getCalendarDateField());
        assertEquals("start_field", savedConfig.getGanttStartDateField());
        assertEquals("title_field", savedConfig.getGalleryTitleField());
    }
}
