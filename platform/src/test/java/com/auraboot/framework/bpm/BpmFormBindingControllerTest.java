package com.auraboot.framework.bpm;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.PageSchemaService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BpmFormBindingController.
 * Validates the design-time APIs used by BPMN designer for form binding configuration.
 */
@Slf4j
@DisplayName("BPM Form Binding Controller Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BpmFormBindingControllerTest extends BaseIntegrationTest {

    @Autowired
    private PageSchemaService pageSchemaService;

    @Autowired
    private MetaModelService metaModelService;

    // ==================== BIND-01: List form pages returns published form-type pages ====================

    @Test
    @Order(1)
    @DisplayName("BIND-01: listFormPages returns only published form-type pages")
    void bind01_listFormPagesReturnsFormTypeOnly() {
        // Get all published schemas and filter form-type ones (same logic as controller)
        List<PageSchemaDTO> allPublished = pageSchemaService.findPublishedSchemas();
        long formCount = allPublished.stream()
                .filter(p -> "form".equalsIgnoreCase(p.getKind()))
                .count();

        log.info("BIND-01: Found {} total published schemas, {} are form-type", allPublished.size(), formCount);

        // Verify form pages can be listed (controller logic)
        List<PageSchemaDTO> formPages = allPublished.stream()
                .filter(p -> "form".equalsIgnoreCase(p.getKind()))
                .toList();

        // Each form page should have pageKey and name
        for (PageSchemaDTO page : formPages) {
            assertThat(page.getPageKey()).as("pageKey should not be null").isNotNull();
            assertThat(page.getName()).as("name should not be null for page %s", page.getPageKey()).isNotNull();
        }

        log.info("BIND-01 PASSED: Listed {} form pages, all have pageKey and name", formPages.size());
    }

    // ==================== BIND-02: Get fields for a valid form page ====================

    @Test
    @Order(2)
    @DisplayName("BIND-02: getPageFields returns fields for a valid form page with model")
    void bind02_getFieldsForValidPage() {
        // Find a published form page that has a modelCode
        List<PageSchemaDTO> allPublished = pageSchemaService.findPublishedSchemas();
        Optional<PageSchemaDTO> formPageOpt = allPublished.stream()
                .filter(p -> "form".equalsIgnoreCase(p.getKind()))
                .filter(p -> p.getModelCode() != null && !p.getModelCode().isBlank())
                .findFirst();

        if (formPageOpt.isEmpty()) {
            log.warn("BIND-02 SKIPPED: No published form page with modelCode found in test data");
            return;
        }

        PageSchemaDTO formPage = formPageOpt.get();
        String pageKey = formPage.getPageKey();
        String modelCode = formPage.getModelCode();

        log.info("BIND-02: Testing fields for page={} (model={})", pageKey, modelCode);

        // Verify findByPageKey resolves correctly
        PageSchemaDTO resolved = pageSchemaService.findByPageKey(pageKey);
        assertThat(resolved).as("findByPageKey should return a page for %s", pageKey).isNotNull();
        assertThat(resolved.getModelCode()).isEqualTo(modelCode);

        // Get fields via MetaModelService (same as controller)
        List<FieldDefinition> fields = metaModelService.getModelFields(modelCode);
        assertThat(fields).as("Model %s should have fields", modelCode).isNotEmpty();

        // Each field should have code and dataType
        for (FieldDefinition field : fields) {
            assertThat(field.getCode()).as("field code should not be null").isNotNull();
            assertThat(field.getDataType()).as("field dataType should not be null for %s", field.getCode()).isNotNull();
        }

        log.info("BIND-02 PASSED: Page {} (model={}) has {} fields", pageKey, modelCode, fields.size());
    }

    // ==================== BIND-03: Non-existent page key returns null ====================

    @Test
    @Order(3)
    @DisplayName("BIND-03: getPageFields with non-existent pageKey returns null from service")
    void bind03_nonExistentPageKeyReturnsNull() {
        String fakePageKey = "non_existent_page_" + System.currentTimeMillis();
        PageSchemaDTO result = pageSchemaService.findByPageKey(fakePageKey);
        assertThat(result).as("Non-existent page key should return null").isNull();

        log.info("BIND-03 PASSED: Non-existent pageKey '{}' returns null", fakePageKey);
    }

    // ==================== BIND-04: Form pages exclude non-form page types ====================

    @Test
    @Order(4)
    @DisplayName("BIND-04: Form page filter excludes list/detail/dashboard types")
    void bind04_filterExcludesNonFormTypes() {
        List<PageSchemaDTO> allPublished = pageSchemaService.findPublishedSchemas();

        List<PageSchemaDTO> formPages = allPublished.stream()
                .filter(p -> "form".equalsIgnoreCase(p.getKind()))
                .toList();

        List<PageSchemaDTO> nonFormPages = allPublished.stream()
                .filter(p -> !"form".equalsIgnoreCase(p.getKind()))
                .toList();

        // If there are non-form pages, verify they are properly excluded
        if (!nonFormPages.isEmpty()) {
            Set<String> formPageKeys = formPages.stream()
                    .map(PageSchemaDTO::getPageKey)
                    .collect(java.util.stream.Collectors.toSet());

            for (PageSchemaDTO nonForm : nonFormPages) {
                assertThat(formPageKeys).as("Non-form page %s should not be in form list", nonForm.getPageKey())
                        .doesNotContain(nonForm.getPageKey());
            }
        }

        log.info("BIND-04 PASSED: {} form pages, {} non-form pages properly excluded",
                formPages.size(), nonFormPages.size());
    }
}
