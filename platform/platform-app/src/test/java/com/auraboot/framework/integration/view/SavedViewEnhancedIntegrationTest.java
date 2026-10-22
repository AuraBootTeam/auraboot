package com.auraboot.framework.integration.view;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.view.dto.SavedViewCreateRequest;
import com.auraboot.framework.view.dto.SavedViewDTO;
import com.auraboot.framework.view.entity.ViewConfig;
import com.auraboot.framework.view.service.SavedViewService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String TEST_MODEL_CODE = "saved_view_enhanced_test";
    private static final String TEST_TABLE_NAME = "mt_saved_view_enhanced_test";
    private static final String FIXTURE_REF = "SavedViewEnhancedIntegrationTest";

    @BeforeEach
    void ensureSavedViewFixture() {
        Long tenantId = getTestTenant().getId();
        jdbcTemplate.update("""
                DELETE FROM ab_meta_model_field_binding
                WHERE model_id IN (
                    SELECT id FROM ab_meta_model WHERE tenant_id = ? AND code = ?
                )
                """, tenantId, TEST_MODEL_CODE);
        jdbcTemplate.update("""
                DELETE FROM ab_meta_field
                WHERE tenant_id = ?
                  AND code IN (
                      'sc_name', 'sc_description', 'sc_start_date', 'sc_end_date',
                      'sc_created_at', 'sc_status', 'sc_progress', 'sc_owner_user',
                      'sc_attachment_file'
                  )
                """, tenantId);
        jdbcTemplate.update("""
                DELETE FROM ab_meta_model
                WHERE tenant_id = ? AND code = ?
                """, tenantId, TEST_MODEL_CODE);

        Long modelId = jdbcTemplate.queryForObject("""
                INSERT INTO ab_meta_model (
                    pid, tenant_id, code, table_name, extension, capabilities,
                    version, is_current, status, deleted_flag
                )
                VALUES (?, ?, ?, ?, ?::jsonb, '{}'::jsonb, 1, TRUE, 'published', FALSE)
                RETURNING id
                """, Long.class, UlidGenerator.generate(), tenantId, TEST_MODEL_CODE, TEST_TABLE_NAME,
                "{\"displayName\":\"Saved View Enhanced Test\",\"testFixture\":\"" + FIXTURE_REF + "\"}");

        bindFixtureField(tenantId, modelId, "sc_name", "string", 0);
        bindFixtureField(tenantId, modelId, "sc_description", "text", 1);
        bindFixtureField(tenantId, modelId, "sc_start_date", "date", 2);
        bindFixtureField(tenantId, modelId, "sc_end_date", "date", 3);
        bindFixtureField(tenantId, modelId, "sc_created_at", "datetime", 4);
        bindFixtureField(tenantId, modelId, "sc_status", "enum", 5);
        bindFixtureField(tenantId, modelId, "sc_progress", "integer", 6);
        bindFixtureField(tenantId, modelId, "sc_owner_user", "reference", 7);
        bindFixtureField(tenantId, modelId, "sc_attachment_file", "file", 8);
    }

    private void bindFixtureField(Long tenantId, Long modelId, String code, String dataType, int order) {
        Long fieldId = jdbcTemplate.queryForObject("""
                INSERT INTO ab_meta_field (
                    pid, tenant_id, version, is_current, status, deleted_flag,
                    code, data_type, extension, index_hint, ui_schema, query_schema
                )
                VALUES (?, ?, 1, TRUE, 'published', FALSE,
                        ?, ?, ?::jsonb, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb)
                RETURNING id
                """, Long.class, UlidGenerator.generate(), tenantId, code, dataType,
                "{\"testFixture\":\"" + FIXTURE_REF + "\"}");

        jdbcTemplate.update("""
                INSERT INTO ab_meta_model_field_binding (
                    pid, tenant_id, model_id, field_id, field_order,
                    required, visible, editable, searchable, deleted_flag, remarks
                )
                VALUES (?, ?, ?, ?, ?, FALSE, TRUE, TRUE, FALSE, FALSE, ?)
                """, UlidGenerator.generate(), tenantId, modelId, fieldId, order, FIXTURE_REF);
    }

    // ==================== Calendar View ====================

    @Test
    @Order(1)
    @DisplayName("Create CALENDAR view with full config")
    void test01_createCalendarView() {
        ViewConfig config = new ViewConfig();
        config.setCalendarDateField("sc_start_date");
        config.setCalendarTitleField("sc_name");
        config.setCalendarEndDateField("sc_end_date");
        config.setCalendarColorField("sc_status");
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
        assertEquals("sc_start_date", savedConfig.getCalendarDateField());
        assertEquals("sc_name", savedConfig.getCalendarTitleField());
        assertEquals("sc_end_date", savedConfig.getCalendarEndDateField());
        assertEquals("sc_status", savedConfig.getCalendarColorField());
        assertEquals("dayGridMonth", savedConfig.getCalendarDefaultView());

        log.info("Created CALENDAR view with pid={}", result.getPid());
    }

    @Test
    @Order(2)
    @DisplayName("Create CALENDAR view with minimal config")
    void test02_createCalendarViewMinimal() {
        ViewConfig config = new ViewConfig();
        config.setCalendarDateField("sc_start_date");

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
        assertEquals("sc_start_date", savedConfig.getCalendarDateField());
        assertNull(savedConfig.getCalendarEndDateField());
        assertNull(savedConfig.getCalendarColorField());
    }

    // ==================== Gallery View ====================

    @Test
    @Order(3)
    @DisplayName("Create GALLERY view with full config")
    void test03_createGalleryView() {
        ViewConfig config = new ViewConfig();
        config.setGalleryImageField("sc_attachment_file");
        config.setGalleryTitleField("sc_name");
        config.setGalleryDescriptionField("sc_description");
        config.setGalleryColumns(4);
        config.setGalleryAspectRatio("16:9");
        config.setGalleryShowTitle(true);
        config.setGalleryShowDescription(true);
        config.setGalleryDisplayFields(List.of("sc_status", "sc_created_at"));

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
        assertEquals("sc_attachment_file", savedConfig.getGalleryImageField());
        assertEquals("sc_name", savedConfig.getGalleryTitleField());
        assertEquals("sc_description", savedConfig.getGalleryDescriptionField());
        assertEquals(4, savedConfig.getGalleryColumns());
        assertEquals("16:9", savedConfig.getGalleryAspectRatio());
        assertTrue(savedConfig.getGalleryShowTitle());
        assertTrue(savedConfig.getGalleryShowDescription());
        assertEquals(2, savedConfig.getGalleryDisplayFields().size());
        assertTrue(savedConfig.getGalleryDisplayFields().contains("sc_status"));

        log.info("Created GALLERY view with pid={}", result.getPid());
    }

    @Test
    @Order(4)
    @DisplayName("Create GALLERY view with default columns")
    void test04_createGalleryViewDefaults() {
        ViewConfig config = new ViewConfig();
        config.setGalleryImageField("sc_attachment_file");
        config.setGalleryTitleField("sc_name");

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
        assertEquals("sc_attachment_file", savedConfig.getGalleryImageField());
        assertEquals("sc_name", savedConfig.getGalleryTitleField());
        // Columns should be null (frontend defaults to 3)
        assertNull(savedConfig.getGalleryColumns());
    }

    // ==================== Gantt View ====================

    @Test
    @Order(5)
    @DisplayName("Create GANTT view with full config")
    void test05_createGanttView() {
        ViewConfig config = new ViewConfig();
        config.setGanttStartDateField("sc_start_date");
        config.setGanttEndDateField("sc_end_date");
        config.setGanttTitleField("sc_name");
        config.setGanttProgressField("sc_progress");
        config.setGanttDependencyField("sc_owner_user");
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
        assertEquals("sc_start_date", savedConfig.getGanttStartDateField());
        assertEquals("sc_end_date", savedConfig.getGanttEndDateField());
        assertEquals("sc_name", savedConfig.getGanttTitleField());
        assertEquals("sc_progress", savedConfig.getGanttProgressField());
        assertEquals("sc_owner_user", savedConfig.getGanttDependencyField());
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
        config.setCalendarDateField("sc_created_at");
        config.setCalendarTitleField("sc_name");
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
        assertEquals("sc_created_at", readConfig.getCalendarDateField());
        assertEquals("sc_name", readConfig.getCalendarTitleField());
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
        config.setCalendarDateField("sc_start_date");
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
        assertEquals("sc_start_date", savedConfig.getCalendarDateField());
        assertEquals("start_field", savedConfig.getGanttStartDateField());
        assertEquals("title_field", savedConfig.getGalleryTitleField());
    }
}
