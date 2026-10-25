package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.RecordCapabilities;
import com.auraboot.framework.meta.dto.RecordCapabilities.ActionCapability;
import com.auraboot.framework.meta.dto.RecordCapabilities.TabCapability;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ARCH-001: RecordCapabilityService Integration Test.
 * <p>
 * Validates that the capability API correctly:
 * <ul>
 *   <li>Returns contextual actions (state_transition, update, delete) for a model</li>
 *   <li>Excludes non-contextual types (query, create)</li>
 *   <li>Filters state_transition commands by record state</li>
 *   <li>Returns actions sorted by priority</li>
 *   <li>Respects platform and context filtering for action bar visibility</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 3.1.0
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("RecordCapabilityService Integration Test - ARCH-001")
class RecordCapabilityServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RecordCapabilityService recordCapabilityService;

    @Autowired
    private CommandService commandService;

    @Autowired
    private DynamicDataService dynamicDataService;

    private static final String TEST_MODEL = "cap_test_" + System.currentTimeMillis();

    /**
     * Create a command with specific execution config for testing.
     */
    private CommandDefinitionDTO createCommand(String suffix, String executionConfig) {
        String code = TEST_MODEL + ":" + suffix + "_" + System.currentTimeMillis();
        CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
        request.setCode(code);
        request.setDisplayName("Test " + suffix);
        request.setDescription("Capability test command");
        request.setModelCode(TEST_MODEL);
        request.setInputSchema("{}");
        request.setExecutionConfig(executionConfig);
        CommandDefinitionDTO created = commandService.create(request);
        return commandService.publish(created.getPid());
    }

    @Test
    @Order(1)
    @DisplayName("ARCH-001.1: Returns contextual actions, excludes query/create types")
    void shouldReturnContextualActionsOnly() {
        createCommand("update", "{\"type\":\"update\"}");
        createCommand("delete", "{\"type\":\"delete\"}");
        createCommand("query", "{\"type\":\"query\"}");
        createCommand("create", "{\"type\":\"create\"}");
        createCommand("action", "{\"type\":\"action\"}");

        RecordCapabilities caps = recordCapabilityService.getRecordCapabilities(
                TEST_MODEL, "0", "web", "detail", getTestUser().getId());

        assertNotNull(caps);
        assertNotNull(caps.getCapabilities());

        // The service maps internal types to capability types:
        // update -> edit_field, delete -> destructive, action -> navigate
        List<String> returnedTypes = caps.getCapabilities().stream()
                .map(ActionCapability::getType)
                .toList();

        assertThat(returnedTypes).containsAnyOf("edit_field", "destructive", "navigate");
        // query and create types should NOT appear
        assertThat(returnedTypes).doesNotContain("query", "create");

        log.info("Returned {} contextual actions, types: {}", caps.getCapabilities().size(), returnedTypes);
    }

    @Test
    @Order(2)
    @DisplayName("ARCH-001.2: Actions are sorted by priority (state_transition first, delete last)")
    void shouldSortByPriority() {
        String model = "cap_sort_" + System.currentTimeMillis();

        createCommandForModel(model, "delete_rec", "{\"type\":\"delete\"}");
        createCommandForModel(model, "transition", "{\"type\":\"state_transition\",\"stateField\":\"status\",\"fromStates\":[\"draft\"]}");
        createCommandForModel(model, "custom_act", "{\"type\":\"action\"}");

        RecordCapabilities caps = recordCapabilityService.getRecordCapabilities(
                model, "0", "web", "detail", getTestUser().getId());

        assertNotNull(caps);
        assertThat(caps.getCapabilities()).hasSizeGreaterThanOrEqualTo(2);

        // Verify ordering by priority values
        List<Integer> priorities = caps.getCapabilities().stream()
                .map(ActionCapability::getPriority)
                .toList();

        // priorities should be non-decreasing
        for (int i = 1; i < priorities.size(); i++) {
            assertThat(priorities.get(i)).isGreaterThanOrEqualTo(priorities.get(i - 1));
        }

        log.info("Actions sorted by priority: {}", priorities);
    }

    @Test
    @Order(3)
    @DisplayName("ARCH-001.3: State transition filtered by record state")
    void shouldFilterStateTransitionByRecordState() {
        String model = "cap_state_" + System.currentTimeMillis();

        createCommandForModel(model, "approve",
                "{\"type\":\"state_transition\",\"stateField\":\"status\",\"fromStates\":[\"pending\"],\"toState\":\"approved\"}");
        createCommandForModel(model, "update_rec", "{\"type\":\"update\"}");

        // With record "0" (non-existent), state_transition should be filtered out
        RecordCapabilities caps = recordCapabilityService.getRecordCapabilities(
                model, "0", "web", "detail", getTestUser().getId());

        assertNotNull(caps);
        List<String> types = caps.getCapabilities().stream()
                .map(ActionCapability::getType)
                .toList();

        // state_transition should be excluded because record is null (state check fails)
        assertThat(types).doesNotContain("state_transition");
        // update (mapped to edit_field) should still be present
        assertThat(types).contains("edit_field");

        log.info("State transition correctly filtered out for missing record");
    }

    @Test
    @Order(4)
    @DisplayName("ARCH-001.4: Destructive actions are not shown in action bar")
    void shouldExcludeDestructiveFromActionBar() {
        String model = "cap_mobile_" + System.currentTimeMillis();

        createCommandForModel(model, "edit", "{\"type\":\"update\"}");
        createCommandForModel(model, "remove", "{\"type\":\"delete\"}");
        createCommandForModel(model, "do_action", "{\"type\":\"action\"}");

        RecordCapabilities caps = recordCapabilityService.getRecordCapabilities(
                model, "0", "mobile", "detail", getTestUser().getId());

        assertNotNull(caps);
        assertThat(caps.getCapabilities()).isNotEmpty();

        // Destructive actions should NOT be in the action bar
        for (ActionCapability action : caps.getCapabilities()) {
            if (action.isDestructive()) {
                assertFalse(action.isShowInActionBar(),
                        "Destructive action should NOT show in action bar");
            }
        }

        log.info("Destructive actions correctly excluded from action bar");
    }

    @Test
    @Order(5)
    @DisplayName("ARCH-001.5: Response includes default tabs")
    void shouldReturnDefaultTabs() {
        String model = "cap_tabs_" + System.currentTimeMillis();
        createCommandForModel(model, "edit", "{\"type\":\"update\"}");

        RecordCapabilities caps = recordCapabilityService.getRecordCapabilities(
                model, "0", "web", "detail", getTestUser().getId());

        assertNotNull(caps);
        assertNotNull(caps.getTabs());
        assertThat(caps.getTabs()).isNotEmpty();

        List<String> tabCodes = caps.getTabs().stream()
                .map(TabCapability::getCode)
                .toList();
        // Default tabs include overview, activity, related, discussion
        assertThat(tabCodes).containsAnyOf("overview", "activity", "related", "discussion");

        log.info("Default tabs: {}", tabCodes);
    }

    @Test
    @Order(6)
    @DisplayName("ARCH-001.6: ActionCapability has correct fields populated")
    void shouldPopulateActionCapabilityFields() {
        String model = "cap_fields_" + System.currentTimeMillis();
        createCommandForModel(model, "edit_record", "{\"type\":\"update\"}");

        RecordCapabilities caps = recordCapabilityService.getRecordCapabilities(
                model, "0", "web", "detail", getTestUser().getId());

        assertNotNull(caps);
        assertThat(caps.getCapabilities()).isNotEmpty();

        ActionCapability editAction = caps.getCapabilities().stream()
                .filter(a -> "edit_field".equals(a.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected edit_field action (mapped from update)"));

        assertThat(editAction.getCode()).isNotBlank();
        assertThat(editAction.getLabel()).isNotBlank();
        assertThat(editAction.getType()).isEqualTo("edit_field");
        assertThat(editAction.getStyle()).isEqualTo("secondary");
        assertThat(editAction.getExecutionMode()).isEqualTo("form_page");

        log.info("ActionCapability fields verified: code={}, style={}, mode={}, priority={}",
                editAction.getCode(), editAction.getStyle(),
                editAction.getExecutionMode(), editAction.getPriority());
    }

    @Test
    @Order(7)
    @DisplayName("ARCH-001.7: Delete action has danger style")
    void shouldAssignDangerStyleToDelete() {
        String model = "cap_danger_" + System.currentTimeMillis();
        createCommandForModel(model, "remove", "{\"type\":\"delete\"}");

        RecordCapabilities caps = recordCapabilityService.getRecordCapabilities(
                model, "0", "web", "detail", getTestUser().getId());

        ActionCapability deleteAction = caps.getCapabilities().stream()
                .filter(a -> a.isDestructive())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected destructive action"));

        assertThat(deleteAction.getStyle()).isEqualTo("danger");
        assertThat(deleteAction.getExecutionMode()).isEqualTo("confirm_dialog");

        log.info("Delete action verified: style=danger, mode=confirm_dialog");
    }

    @Test
    @Order(8)
    @DisplayName("ARCH-001.8: recordState is null when record does not exist")
    void shouldReturnNullRecordStateWhenRecordMissing() {
        String model = "cap_rs_null_" + System.currentTimeMillis();
        createCommandForModel(model, "edit", "{\"type\":\"update\"}");

        RecordCapabilities caps = recordCapabilityService.getRecordCapabilities(
                model, "nonexistent-id", "web", "detail", getTestUser().getId());

        assertNotNull(caps);
        assertThat(caps.getRecordState()).isNull();

        log.info("recordState correctly null for missing record");
    }

    @Test
    @Order(9)
    @DisplayName("ARCH-001.9: destructive=true for delete-type actions and danger style")
    void shouldMarkDeleteActionsAsDestructive() {
        String model = "cap_destr_" + System.currentTimeMillis();
        createCommandForModel(model, "remove", "{\"type\":\"delete\"}");
        createCommandForModel(model, "edit", "{\"type\":\"update\"}");
        createCommandForModel(model, "force_action", "{\"type\":\"action\",\"style\":\"danger\"}");

        RecordCapabilities caps = recordCapabilityService.getRecordCapabilities(
                model, "0", "web", "detail", getTestUser().getId());

        assertNotNull(caps);
        assertThat(caps.getCapabilities()).isNotEmpty();

        // delete type (mapped to "destructive") -> destructive=true
        ActionCapability deleteAction = caps.getCapabilities().stream()
                .filter(a -> "destructive".equals(a.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected destructive-type action"));
        assertThat(deleteAction.isDestructive())
                .as("delete-type action must be destructive")
                .isTrue();

        // update type (mapped to "edit_field") -> destructive=false
        ActionCapability updateAction = caps.getCapabilities().stream()
                .filter(a -> "edit_field".equals(a.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected edit_field action"));
        assertThat(updateAction.isDestructive())
                .as("update-type action must not be destructive")
                .isFalse();

        // action type with style=danger -> destructive=true
        ActionCapability dangerAction = caps.getCapabilities().stream()
                .filter(a -> "navigate".equals(a.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected navigate action with danger style"));
        assertThat(dangerAction.isDestructive())
                .as("action with danger style must be destructive")
                .isTrue();

        log.info("destructive flag verified: delete={}, update={}, dangerAction={}",
                deleteAction.isDestructive(), updateAction.isDestructive(), dangerAction.isDestructive());
    }

    @Test
    @Order(10)
    @DisplayName("ARCH-001.10: commandCode is populated for all actions")
    void shouldPopulateCommandCodeForAllActions() {
        String model = "cap_cmdcode_" + System.currentTimeMillis();
        createCommandForModel(model, "edit", "{\"type\":\"update\"}");
        createCommandForModel(model, "remove", "{\"type\":\"delete\"}");
        createCommandForModel(model, "do_action", "{\"type\":\"action\"}");

        RecordCapabilities caps = recordCapabilityService.getRecordCapabilities(
                model, "0", "web", "detail", getTestUser().getId());

        assertNotNull(caps);
        assertThat(caps.getCapabilities()).isNotEmpty();

        for (ActionCapability action : caps.getCapabilities()) {
            assertThat(action.getCommandCode())
                    .as("commandCode must be non-blank for action type=%s code=%s",
                            action.getType(), action.getCode())
                    .isNotBlank();
            assertThat(action.getCommandCode())
                    .startsWith(model + ":");
        }

        log.info("commandCode populated for {} actions", caps.getCapabilities().size());
    }

    /**
     * Helper to create a command for a specific model code.
     */
    private CommandDefinitionDTO createCommandForModel(String modelCode, String suffix, String executionConfig) {
        String code = modelCode + ":" + suffix + "_" + System.currentTimeMillis();
        CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
        request.setCode(code);
        request.setDisplayName("Test " + suffix);
        request.setDescription("Capability test command");
        request.setModelCode(modelCode);
        request.setInputSchema("{}");
        request.setExecutionConfig(executionConfig);
        CommandDefinitionDTO created = commandService.create(request);
        return commandService.publish(created.getPid());
    }
}
