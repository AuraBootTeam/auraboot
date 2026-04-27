package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CommandService Integration Test
 *
 * Covers P1-1 requirements:
 * 1. CommandDefinition CRUD (create, read, update, delete)
 * 2. BindingRule management (add, remove, get, reorder)
 * 3. Command publishing and version control
 * 4. Code uniqueness constraints
 * 5. Model association
 *
 * Each test is self-contained and creates its own test data.
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("CommandService Integration Test - P1-1")
class CommandServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CommandService commandService;

    private static final String TEST_MODEL_CODE = "test_model";

    /**
     * Helper method to create a command for testing
     */
    private CommandDefinitionDTO createTestCommand(String suffix) {
        String code = "cmd_test_" + System.currentTimeMillis() + "_" + suffix;
        CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
        request.setCode(code);
        request.setDisplayName("Test Command " + suffix);
        request.setDescription("Integration test command");
        request.setModelCode(TEST_MODEL_CODE);
        request.setInputSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");
        return commandService.create(request);
    }

    // ==================== CRUD Tests ====================

    @Test
    @Order(1)
    @DisplayName("P1-1.1: Create command definition")
    void test01_createCommand() {
        String code = "cmd_create_" + System.currentTimeMillis();
        CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
        request.setCode(code);
        request.setDisplayName("Test Command");
        request.setDescription("Integration test command");
        request.setModelCode(TEST_MODEL_CODE);
        request.setInputSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");

        CommandDefinitionDTO result = commandService.create(request);

        assertNotNull(result);
        assertNotNull(result.getPid());
        assertEquals(code, result.getCode());
        assertEquals("Test Command", result.getDisplayName());
        assertEquals(TEST_MODEL_CODE, result.getModelCode());
        assertNotNull(result.getStatus());

        log.info("Created command: pid={}, code={}", result.getPid(), code);
    }

    @Test
    @Order(1)
    @DisplayName("P1-1.1: Create command preserves agent risk level")
    void test01b_createCommandPreservesRiskLevel() {
        String code = "cmd_risk_" + System.currentTimeMillis();
        CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
        request.setCode(code);
        request.setDisplayName("Risk Command");
        request.setDescription("Integration test command with risk level");
        request.setModelCode(TEST_MODEL_CODE);
        request.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
        request.setCmdRiskLevel("L3");

        CommandDefinitionDTO created = commandService.create(request);
        CommandDefinitionDTO result = commandService.findByCode(created.getCode());

        assertEquals("L3", result.getCmdRiskLevel());
    }

    @Test
    @Order(2)
    @DisplayName("P1-1.1: Find command by PID")
    void test02_findByPid() {
        // Create a command first
        CommandDefinitionDTO created = createTestCommand("findByPid");
        assertNotNull(created.getPid());

        // Find by PID
        CommandDefinitionDTO result = commandService.findByPid(created.getPid());

        assertNotNull(result);
        assertEquals(created.getPid(), result.getPid());
        assertEquals(created.getCode(), result.getCode());
    }

    @Test
    @Order(3)
    @DisplayName("P1-1.1: Find command by code")
    void test03_findByCode() {
        // Create a command first
        CommandDefinitionDTO created = createTestCommand("findByCode");

        // Find by code
        CommandDefinitionDTO result = commandService.findByCode(created.getCode());

        assertNotNull(result);
        assertEquals(created.getCode(), result.getCode());
    }

    @Test
    @Order(4)
    @DisplayName("P1-1.1: Find non-existent command should throw")
    void test04_findByPid_notFound() {
        assertThrows(Exception.class, () -> {
            commandService.findByPid("non_existent_pid");
        });
    }

    @Test
    @Order(5)
    @DisplayName("P1-1.1: List commands by model code")
    void test05_listByModelCode() {
        // Create a command first
        CommandDefinitionDTO created = createTestCommand("listByModel");

        // List by model code
        List<CommandDefinitionDTO> commands = commandService.listByModelCode(TEST_MODEL_CODE);

        assertNotNull(commands);
        assertTrue(commands.stream().anyMatch(c -> created.getCode().equals(c.getCode())));
    }

    @Test
    @Order(10)
    @DisplayName("P1-1.1: Update command definition")
    void test10_updateCommand() {
        // Create a command first
        CommandDefinitionDTO created = createTestCommand("update");
        assertNotNull(created.getPid());

        // Update it
        CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
        request.setCode(created.getCode());
        request.setDisplayName("Updated Command");
        request.setDescription("Updated description");
        request.setModelCode(TEST_MODEL_CODE);

        CommandDefinitionDTO result = commandService.update(created.getPid(), request);

        assertNotNull(result);
        assertEquals("Updated Command", result.getDisplayName());
    }

    @Test
    @Order(11)
    @DisplayName("P1-1.1: Update non-existent command should throw")
    void test11_updateCommand_notFound() {
        CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
        request.setCode("dummy");
        request.setModelCode(TEST_MODEL_CODE);

        assertThrows(Exception.class, () -> {
            commandService.update("non_existent_pid", request);
        });
    }

    // ==================== BindingRule Tests ====================

    @Test
    @Order(20)
    @DisplayName("P1-1.2: Add ASSERT binding rule")
    void test20_addBindingRule_assert() {
        // Create a command first
        CommandDefinitionDTO created = createTestCommand("assertRule");
        assertNotNull(created.getPid());

        BindingRuleDTO rule = new BindingRuleDTO();
        rule.setRuleType("assert");
        rule.setExpression("#payload['name'] != null && !#payload['name'].isEmpty()");
        rule.setSequence(1);
        rule.setEnabled(true);

        BindingRuleDTO result = commandService.addBindingRule(created.getPid(), rule);

        assertNotNull(result);
        assertNotNull(result.getId());
        assertEquals("assert", result.getRuleType());
        assertEquals(1, result.getSequence());
    }

    @Test
    @Order(21)
    @DisplayName("P1-1.2: Add FIELD_MAP binding rule")
    void test21_addBindingRule_fieldMap() {
        // Create a command first
        CommandDefinitionDTO created = createTestCommand("fieldMapRule");
        assertNotNull(created.getPid());

        BindingRuleDTO rule = new BindingRuleDTO();
        rule.setRuleType("field_map");
        rule.setSourceField("name");
        rule.setTargetModel(TEST_MODEL_CODE);
        rule.setTargetField("name");
        rule.setSequence(2);
        rule.setEnabled(true);

        BindingRuleDTO result = commandService.addBindingRule(created.getPid(), rule);

        assertNotNull(result);
        assertEquals("field_map", result.getRuleType());
        assertEquals("name", result.getSourceField());
        assertEquals("name", result.getTargetField());
    }

    @Test
    @Order(22)
    @DisplayName("P1-1.2: Add EFFECT binding rule")
    void test22_addBindingRule_effect() {
        // Create a command first
        CommandDefinitionDTO created = createTestCommand("effectRule");
        assertNotNull(created.getPid());

        BindingRuleDTO rule = new BindingRuleDTO();
        rule.setRuleType("effect");
        rule.setEventType("record_created");
        rule.setSequence(3);
        rule.setEnabled(true);

        BindingRuleDTO result = commandService.addBindingRule(created.getPid(), rule);

        assertNotNull(result);
        assertEquals("effect", result.getRuleType());
        assertEquals("record_created", result.getEventType());
    }

    @Test
    @Order(23)
    @DisplayName("P1-1.2: Get all binding rules for command")
    void test23_getBindingRules() {
        // Create a command first
        CommandDefinitionDTO created = createTestCommand("getRules");
        assertNotNull(created.getPid());

        // Add multiple rules
        BindingRuleDTO assertRule = new BindingRuleDTO();
        assertRule.setRuleType("assert");
        assertRule.setExpression("true");
        assertRule.setSequence(1);
        assertRule.setEnabled(true);
        commandService.addBindingRule(created.getPid(), assertRule);

        BindingRuleDTO fieldMapRule = new BindingRuleDTO();
        fieldMapRule.setRuleType("field_map");
        fieldMapRule.setSourceField("name");
        fieldMapRule.setTargetModel(TEST_MODEL_CODE);
        fieldMapRule.setTargetField("name");
        fieldMapRule.setSequence(2);
        fieldMapRule.setEnabled(true);
        commandService.addBindingRule(created.getPid(), fieldMapRule);

        BindingRuleDTO effectRule = new BindingRuleDTO();
        effectRule.setRuleType("effect");
        effectRule.setEventType("record_created");
        effectRule.setSequence(3);
        effectRule.setEnabled(true);
        commandService.addBindingRule(created.getPid(), effectRule);

        // Get all rules
        List<BindingRuleDTO> rules = commandService.getBindingRules(created.getPid());

        assertNotNull(rules);
        assertTrue(rules.size() >= 3, "Should have at least 3 rules (ASSERT, FIELD_MAP, EFFECT)");

        // Verify rule types
        List<String> types = rules.stream().map(BindingRuleDTO::getRuleType).toList();
        assertTrue(types.contains("assert"));
        assertTrue(types.contains("field_map"));
        assertTrue(types.contains("effect"));
    }

    @Test
    @Order(24)
    @DisplayName("P1-1.2: Reorder binding rules")
    void test24_reorderBindingRules() {
        // Create a command first
        CommandDefinitionDTO created = createTestCommand("reorder");
        assertNotNull(created.getPid());

        // Add multiple rules
        BindingRuleDTO rule1 = new BindingRuleDTO();
        rule1.setRuleType("assert");
        rule1.setExpression("true");
        rule1.setSequence(1);
        rule1.setEnabled(true);
        BindingRuleDTO added1 = commandService.addBindingRule(created.getPid(), rule1);

        BindingRuleDTO rule2 = new BindingRuleDTO();
        rule2.setRuleType("assert");
        rule2.setExpression("false");
        rule2.setSequence(2);
        rule2.setEnabled(true);
        BindingRuleDTO added2 = commandService.addBindingRule(created.getPid(), rule2);

        // Reverse the order
        List<String> reversedPids = Arrays.asList(added2.getPid(), added1.getPid());

        assertDoesNotThrow(() -> {
            commandService.reorderBindingRules(created.getPid(), reversedPids);
        });
    }

    @Test
    @Order(25)
    @DisplayName("P1-1.2: Remove binding rule")
    void test25_removeBindingRule() {
        // Create a command first
        CommandDefinitionDTO created = createTestCommand("removeRule");
        assertNotNull(created.getPid());

        // Add a rule to remove
        BindingRuleDTO rule = new BindingRuleDTO();
        rule.setRuleType("assert");
        rule.setExpression("true");
        rule.setSequence(99);
        rule.setEnabled(true);
        BindingRuleDTO added = commandService.addBindingRule(created.getPid(), rule);
        assertNotNull(added.getPid());

        // Remove it
        assertDoesNotThrow(() -> {
            commandService.removeBindingRule(added.getPid());
        });

        // Verify removal
        List<BindingRuleDTO> remaining = commandService.getBindingRules(created.getPid());
        assertFalse(remaining.stream().anyMatch(r -> added.getPid().equals(r.getPid())));
    }

    // ==================== Publish Tests ====================

    @Test
    @Order(30)
    @DisplayName("P1-1.3: Publish command definition")
    void test30_publishCommand() {
        // Create a command first
        CommandDefinitionDTO created = createTestCommand("publish");
        assertNotNull(created.getPid());

        // Add at least one binding rule (required for publishing)
        BindingRuleDTO rule = new BindingRuleDTO();
        rule.setRuleType("field_map");
        rule.setSourceField("name");
        rule.setTargetModel(TEST_MODEL_CODE);
        rule.setTargetField("name");
        rule.setSequence(1);
        rule.setEnabled(true);
        commandService.addBindingRule(created.getPid(), rule);

        CommandDefinitionDTO result = commandService.publish(created.getPid());

        assertNotNull(result);
        assertEquals("published", result.getStatus());
    }

    // ==================== Delete Tests ====================

    @Test
    @Order(90)
    @DisplayName("P1-1.1: Delete command definition")
    void test90_deleteCommand() {
        // Create a command specifically to delete
        String deleteCode = "cmd_delete_" + System.currentTimeMillis();
        CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
        request.setCode(deleteCode);
        request.setDisplayName("To Delete");
        request.setModelCode(TEST_MODEL_CODE);

        CommandDefinitionDTO created = commandService.create(request);
        assertNotNull(created.getPid());

        assertDoesNotThrow(() -> {
            commandService.delete(created.getPid());
        });

        assertThrows(Exception.class, () -> {
            commandService.findByPid(created.getPid());
        });
    }

    @Test
    @Order(91)
    @DisplayName("P1-1.1: Delete non-existent command should throw")
    void test91_deleteCommand_notFound() {
        assertThrows(Exception.class, () -> {
            commandService.delete("non_existent_pid");
        });
    }
}
