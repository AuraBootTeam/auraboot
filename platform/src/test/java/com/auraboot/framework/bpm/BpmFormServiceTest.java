package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.service.BpmFormService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for BpmFormService covering form binding resolution,
 * variable bindings for initial values, field permissions, form data
 * to variable mapping, unmapped field passthrough, and multi-binding support.
 */
@Slf4j
@DisplayName("BPM Form Service Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BpmFormServiceTest extends BaseIntegrationTest {

    @Autowired
    private BpmFormService formService;

    // ==================== Helper Methods ====================

    private Map<String, Object> buildBinding(String formRef, String formType, String version,
                                              Map<String, String> variableBindings,
                                              Map<String, String> fieldPermissions) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("formRef", formRef);
        binding.put("formType", formType);
        binding.put("version", version);
        if (variableBindings != null) {
            binding.put("variableBindings", variableBindings);
        }
        if (fieldPermissions != null) {
            binding.put("fieldPermissions", fieldPermissions);
        }
        return binding;
    }

    // ==================== Test Cases ====================

    @Test
    @Order(1)
    @DisplayName("FORM-01: No binding returns hasForm=false")
    void form01_noBindingReturnsNoForm() {
        Map<String, Object> result = formService.getTaskForm(
                "task-01", "procDef-01", "node-01",
                Map.of("var1", "value1"),
                null  // formBindings = null
        );

        assertEquals(false, result.get("hasForm"), "hasForm should be false when formBindings is null");
        assertFalse(result.containsKey("forms"), "forms key should not be present");

        log.info("FORM-01 PASSED: No binding returns hasForm=false");
    }

    @Test
    @Order(2)
    @DisplayName("FORM-02: With binding returns hasForm=true and formRef")
    void form02_withBindingReturnsForm() {
        Map<String, Object> binding = buildBinding(
                "page-order-form", "page_dsl", "1",
                Map.of("amount", "orderAmount"),
                Map.of("amount", "editable"));
        Map<String, Object> formBindings = Map.of("task-node-1", binding);

        Map<String, Object> result = formService.getTaskForm(
                "task-02", "procDef-02", "task-node-1",
                Map.of("orderAmount", 500),
                formBindings
        );

        assertEquals(true, result.get("hasForm"), "hasForm should be true");
        assertEquals("task-02", result.get("taskId"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> forms = (List<Map<String, Object>>) result.get("forms");
        assertNotNull(forms, "forms list should not be null");
        assertEquals(1, forms.size(), "Should have 1 form");
        assertEquals("page-order-form", forms.getFirst().get("formRef"));
        assertEquals("page_dsl", forms.getFirst().get("formType"));

        log.info("FORM-02 PASSED: With binding returns hasForm=true, formRef={}", forms.getFirst().get("formRef"));
    }

    @Test
    @Order(3)
    @DisplayName("FORM-03: Variable bindings map process variables to initial values")
    void form03_variableBindingsMap() {
        Map<String, Object> binding = buildBinding(
                "page-form-03", "page_dsl", "1",
                Map.of("amount", "orderAmount", "title", "orderTitle"),
                null);
        Map<String, Object> formBindings = Map.of("task-node-3", binding);
        Map<String, Object> processVariables = Map.of("orderAmount", 100, "orderTitle", "Test Order");

        Map<String, Object> result = formService.getTaskForm(
                "task-03", "procDef-03", "task-node-3",
                processVariables, formBindings
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> forms = (List<Map<String, Object>>) result.get("forms");
        @SuppressWarnings("unchecked")
        Map<String, Object> initialValues = (Map<String, Object>) forms.getFirst().get("initialValues");

        assertNotNull(initialValues, "initialValues should not be null");
        assertEquals(100, initialValues.get("amount"), "amount should map from orderAmount=100");
        assertEquals("Test Order", initialValues.get("title"), "title should map from orderTitle");

        log.info("FORM-03 PASSED: Variable bindings mapped, initialValues={}", initialValues);
    }

    @Test
    @Order(4)
    @DisplayName("FORM-04: Field permissions present in result")
    void form04_fieldPermissions() {
        Map<String, Object> binding = buildBinding(
                "page-form-04", "page_dsl", "1",
                null,
                Map.of("name", "editable", "status", "readonly", "secret", "hidden"));
        Map<String, Object> formBindings = Map.of("task-node-4", binding);

        Map<String, Object> result = formService.getTaskForm(
                "task-04", "procDef-04", "task-node-4",
                Map.of(), formBindings
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> forms = (List<Map<String, Object>>) result.get("forms");
        @SuppressWarnings("unchecked")
        Map<String, String> permissions = (Map<String, String>) forms.getFirst().get("fieldPermissions");

        assertNotNull(permissions, "fieldPermissions should not be null");
        assertEquals("editable", permissions.get("name"));
        assertEquals("readonly", permissions.get("status"));
        assertEquals("hidden", permissions.get("secret"));

        log.info("FORM-04 PASSED: Field permissions present, permissions={}", permissions);
    }

    @Test
    @Order(5)
    @DisplayName("FORM-05: mapFormDataToVariables maps form fields to process variables")
    void form05_formDataToVariables() {
        Map<String, Object> binding = buildBinding(
                "page-form-05", "page_dsl", "1",
                Map.of("amount", "orderAmount"),
                null);
        Map<String, Object> formBindings = Map.of("task-node-5", binding);
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("amount", 200);

        Map<String, Object> variables = formService.mapFormDataToVariables(formData, "task-node-5", formBindings);

        assertEquals(200, variables.get("orderAmount"), "formData.amount should map to orderAmount");

        log.info("FORM-05 PASSED: Form data mapped to variables, orderAmount={}", variables.get("orderAmount"));
    }

    @Test
    @Order(6)
    @DisplayName("FORM-06: No binding passthrough returns formData as-is")
    void form06_noBindingPassthrough() {
        Map<String, Object> formData = Map.of("fieldA", "valueA", "fieldB", 123);

        Map<String, Object> variables = formService.mapFormDataToVariables(formData, "unknown-node", null);

        assertEquals("valueA", variables.get("fieldA"));
        assertEquals(123, variables.get("fieldB"));

        log.info("FORM-06 PASSED: No binding passthrough, variables={}", variables);
    }

    @Test
    @Order(7)
    @DisplayName("FORM-07: Unmapped fields preserved via putIfAbsent")
    void form07_unmappedFieldsIncluded() {
        Map<String, Object> binding = buildBinding(
                "page-form-07", "page_dsl", "1",
                Map.of("amount", "orderAmount"),
                null);
        Map<String, Object> formBindings = Map.of("task-node-7", binding);

        // formData has both mapped (amount) and unmapped (notes) fields
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("amount", 300);
        formData.put("notes", "extra info");

        Map<String, Object> variables = formService.mapFormDataToVariables(formData, "task-node-7", formBindings);

        assertEquals(300, variables.get("orderAmount"), "Mapped field: amount -> orderAmount");
        assertEquals("extra info", variables.get("notes"), "Unmapped field 'notes' should be preserved");

        log.info("FORM-07 PASSED: Unmapped fields preserved, variables keys={}", variables.keySet());
    }

    @Test
    @Order(8)
    @DisplayName("FORM-08: Multiple bindings for same node produce multiple forms")
    void form08_multipleBindings() {
        Map<String, Object> binding1 = buildBinding(
                "page-form-08a", "page_dsl", "1",
                Map.of("field1", "var1"),
                Map.of("field1", "editable"));
        Map<String, Object> binding2 = buildBinding(
                "page-form-08b", "page_dsl", "2",
                Map.of("field2", "var2"),
                Map.of("field2", "readonly"));

        // Multiple bindings as a List under the same nodeId
        Map<String, Object> formBindings = Map.of("task-node-8", List.of(binding1, binding2));

        Map<String, Object> result = formService.getTaskForm(
                "task-08", "procDef-08", "task-node-8",
                Map.of("var1", "val1", "var2", "val2"),
                formBindings
        );

        assertEquals(true, result.get("hasForm"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> forms = (List<Map<String, Object>>) result.get("forms");
        assertEquals(2, forms.size(), "Should have 2 forms for multiple bindings");
        assertEquals("page-form-08a", forms.get(0).get("formRef"));
        assertEquals("page-form-08b", forms.get(1).get("formRef"));

        log.info("FORM-08 PASSED: Multiple bindings produced {} forms", forms.size());
    }
}
