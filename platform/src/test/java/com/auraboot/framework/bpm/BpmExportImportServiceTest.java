package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.service.BpmExportImportService;
import com.auraboot.framework.bpm.service.BpmNodeHookService;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for BpmExportImportService covering export packaging,
 * validation, and import with multiple conflict resolution strategies
 * (SKIP_EXISTING, OVERWRITE, NEW_VERSION).
 */
@Slf4j
@DisplayName("BPM Export/Import Service Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BpmExportImportServiceTest extends BaseIntegrationTest {

    @Autowired
    private BpmExportImportService exportImportService;

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private BpmNodeHookService hookService;

    @Autowired
    private BpmProcessDefinitionMapper processDefinitionMapper;

    // ==================== Helper Methods ====================

    /**
     * Create and deploy a simple BPMN process for testing.
     */
    private String createAndDeployProcess(String suffix) {
        String processKey = "test-export-" + suffix + "-" + System.nanoTime();
        String bpmn = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" " +
                "targetNamespace=\"http://auraboot.com/bpm\">" +
                "<process id=\"" + processKey + "\" isExecutable=\"true\">" +
                "<startEvent id=\"start\"/>" +
                "<endEvent id=\"end\"/>" +
                "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"end\"/>" +
                "</process></definitions>";

        var def = deploymentService.create(
                new ProcessDeploymentService.CreateProcessRequest(
                        processKey, "Test Process " + suffix, "desc", "test",
                        bpmn, null, null, null));
        deploymentService.deploy(def.getPid());
        return processKey;
    }

    /**
     * Build a valid import package map.
     */
    private Map<String, Object> buildValidPackage(String processKey) {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("format", "aura-bpm-package");
        pkg.put("version", "1.0");
        pkg.put("processKey", processKey);
        pkg.put("processDefinition", Map.of(
                "processKey", processKey,
                "processName", "Import Test " + processKey,
                "bpmnContent", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" " +
                        "targetNamespace=\"http://auraboot.com/bpm\">" +
                        "<process id=\"" + processKey + "\" isExecutable=\"true\">" +
                        "<startEvent id=\"start\"/><endEvent id=\"end\"/>" +
                        "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"end\"/>" +
                        "</process></definitions>"
        ));
        return pkg;
    }

    // ==================== Test Cases ====================

    @Test
    @Order(1)
    @DisplayName("EXPORT-01: Basic export includes format, version, processKey, processDefinition")
    void export01_basicExport() {
        String processKey = createAndDeployProcess("e01");

        Map<String, Object> result = exportImportService.exportPackage(processKey);

        assertEquals("aura-bpm-package", result.get("format"));
        assertEquals("1.0", result.get("version"));
        assertEquals(processKey, result.get("processKey"));
        assertNotNull(result.get("exportedAt"), "exportedAt should be set");
        assertNotNull(result.get("processDefinition"), "processDefinition map should be present");

        @SuppressWarnings("unchecked")
        Map<String, Object> procDef = (Map<String, Object>) result.get("processDefinition");
        assertEquals(processKey, procDef.get("processKey"));

        log.info("EXPORT-01 PASSED: Basic export with format={}, processKey={}", result.get("format"), processKey);
    }

    @Test
    @Order(2)
    @DisplayName("EXPORT-02: Export includes node hooks when present")
    void export02_includesNodeHooks() {
        String processKey = createAndDeployProcess("e02");

        // Create a hook for this process
        BpmNodeHook hook = new BpmNodeHook();
        hook.setProcessKey(processKey);
        hook.setNodeId("approval");
        hook.setHookType("pre_check");
        hook.setHookConfig(Map.of("type", "script", "script", "return true"));
        hook.setFailStrategy("block");
        hook.setEnabled(true);
        hookService.createHook(hook);

        Map<String, Object> result = exportImportService.exportPackage(processKey);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodeHooks = (List<Map<String, Object>>) result.get("nodeHooks");
        assertNotNull(nodeHooks, "nodeHooks should not be null");
        assertFalse(nodeHooks.isEmpty(), "nodeHooks list should not be empty");
        assertEquals("approval", nodeHooks.getFirst().get("nodeId"));

        log.info("EXPORT-02 PASSED: Export includes {} node hooks", nodeHooks.size());
    }

    @Test
    @Order(3)
    @DisplayName("EXPORT-03: Export includes slaConfigs key (may be empty)")
    void export03_includesSlaConfigsKey() {
        String processKey = createAndDeployProcess("e03");

        Map<String, Object> result = exportImportService.exportPackage(processKey);

        assertTrue(result.containsKey("slaConfigs"), "slaConfigs key should exist in export");

        log.info("EXPORT-03 PASSED: slaConfigs key present in export");
    }

    @Test
    @Order(4)
    @DisplayName("EXPORT-04: Export throws for non-existent processKey")
    void export04_notFoundThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> exportImportService.exportPackage("nonexistent-process-key-xyz"),
                "Should throw IllegalArgumentException for non-existent processKey");

        log.info("EXPORT-04 PASSED: IllegalArgumentException thrown for non-existent processKey");
    }

    @Test
    @Order(5)
    @DisplayName("VALIDATE-05: Valid package passes validation")
    void validate05_validPackagePasses() {
        String processKey = "import-valid-05-" + System.nanoTime();
        Map<String, Object> pkg = buildValidPackage(processKey);

        Map<String, Object> result = exportImportService.validatePackage(pkg);

        assertEquals(true, result.get("valid"), "Valid package should pass validation");
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.isEmpty(), "Errors should be empty for valid package");

        log.info("VALIDATE-05 PASSED: Valid package passes validation");
    }

    @Test
    @Order(6)
    @DisplayName("VALIDATE-06: Missing processKey fails validation")
    void validate06_missingFieldsFails() {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("format", "aura-bpm-package");
        // Missing processKey and version

        Map<String, Object> result = exportImportService.validatePackage(pkg);

        assertEquals(false, result.get("valid"), "Package without processKey should fail");
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) result.get("errors");
        assertFalse(errors.isEmpty(), "Errors should not be empty");

        log.info("VALIDATE-06 PASSED: Missing fields causes validation failure, errors={}", errors);
    }

    @Test
    @Order(7)
    @DisplayName("VALIDATE-07: Invalid format fails validation")
    void validate07_invalidFormatFails() {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("format", "wrong-format");
        pkg.put("version", "1.0");
        pkg.put("processKey", "test-key");

        Map<String, Object> result = exportImportService.validatePackage(pkg);

        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) result.get("errors");
        assertFalse(errors.isEmpty(), "Invalid format should produce errors");

        log.info("VALIDATE-07 PASSED: Invalid format causes errors={}", errors);
    }

    @Test
    @Order(8)
    @DisplayName("VALIDATE-08: Conflict detected for existing process")
    void validate08_conflictDetection() {
        String processKey = createAndDeployProcess("v08");

        Map<String, Object> pkg = buildValidPackage(processKey);

        Map<String, Object> result = exportImportService.validatePackage(pkg);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> conflicts = (List<Map<String, String>>) result.get("conflicts");
        assertNotNull(conflicts, "conflicts list should not be null");
        assertFalse(conflicts.isEmpty(), "conflicts should detect existing process");
        assertEquals("process_definition", conflicts.getFirst().get("type"));

        log.info("VALIDATE-08 PASSED: Conflict detected for existing processKey={}", processKey);
    }

    @Test
    @Order(9)
    @DisplayName("IMPORT-09: Import creates new process definition")
    void import09_createNew() {
        String processKey = "import-new-09-" + System.nanoTime();
        Map<String, Object> pkg = buildValidPackage(processKey);

        Map<String, Object> result = exportImportService.executeImport(pkg, "skip_existing");

        assertEquals(true, result.get("success"));
        assertEquals(processKey, result.get("processKey"));

        @SuppressWarnings("unchecked")
        List<String> imported = (List<String>) result.get("imported");
        assertTrue(imported.stream().anyMatch(s -> s.contains("created")),
                "imported should contain 'created', got: " + imported);

        log.info("IMPORT-09 PASSED: New process created, imported={}", imported);
    }

    @Test
    @Order(10)
    @DisplayName("IMPORT-10: SKIP_EXISTING skips existing process")
    void import10_skipExisting() {
        String processKey = createAndDeployProcess("i10");
        Map<String, Object> pkg = buildValidPackage(processKey);

        Map<String, Object> result = exportImportService.executeImport(pkg, "skip_existing");

        assertEquals(true, result.get("success"));

        @SuppressWarnings("unchecked")
        List<String> skipped = (List<String>) result.get("skipped");
        assertTrue(skipped.contains("processDefinition"),
                "skipped should contain 'processDefinition', got: " + skipped);

        log.info("IMPORT-10 PASSED: SKIP_EXISTING skipped={}", skipped);
    }

    @Test
    @Order(11)
    @DisplayName("IMPORT-11: OVERWRITE updates existing process")
    void import11_overwrite() {
        String processKey = createAndDeployProcess("i11");
        Map<String, Object> pkg = buildValidPackage(processKey);

        Map<String, Object> result = exportImportService.executeImport(pkg, "overwrite");

        assertEquals(true, result.get("success"));

        @SuppressWarnings("unchecked")
        List<String> imported = (List<String>) result.get("imported");
        assertTrue(imported.stream().anyMatch(s -> s.contains("overwritten")),
                "imported should contain 'overwritten', got: " + imported);

        log.info("IMPORT-11 PASSED: OVERWRITE imported={}", imported);
    }

    @Test
    @Order(12)
    @DisplayName("IMPORT-12: NEW_VERSION creates new version of existing process")
    void import12_newVersion() {
        String processKey = createAndDeployProcess("i12");
        Map<String, Object> pkg = buildValidPackage(processKey);

        Map<String, Object> result = exportImportService.executeImport(pkg, "new_version");

        assertEquals(true, result.get("success"));

        @SuppressWarnings("unchecked")
        List<String> imported = (List<String>) result.get("imported");
        assertTrue(imported.stream().anyMatch(s -> s.contains("new version")),
                "imported should contain 'new version', got: " + imported);

        log.info("IMPORT-12 PASSED: NEW_VERSION imported={}", imported);
    }

    @Test
    @Order(13)
    @DisplayName("IMPORT-13: Import with nodeHooks creates hooks")
    void import13_importsNodeHooks() {
        String processKey = "import-hooks-13-" + System.nanoTime();
        Map<String, Object> pkg = buildValidPackage(processKey);

        // Add nodeHooks to package
        List<Map<String, Object>> hooks = List.of(
                Map.of(
                        "nodeId", "approval-node",
                        "hookType", "pre_check",
                        "hookConfig", Map.of("type", "script", "script", "return true"),
                        "failStrategy", "block",
                        "async", false,
                        "enabled", true
                )
        );
        pkg.put("nodeHooks", hooks);

        Map<String, Object> result = exportImportService.executeImport(pkg, "skip_existing");

        assertEquals(true, result.get("success"));

        @SuppressWarnings("unchecked")
        List<String> imported = (List<String>) result.get("imported");
        assertTrue(imported.stream().anyMatch(s -> s.contains("nodeHooks")),
                "imported should contain 'nodeHooks', got: " + imported);

        log.info("IMPORT-13 PASSED: nodeHooks imported, imported={}", imported);
    }
}
