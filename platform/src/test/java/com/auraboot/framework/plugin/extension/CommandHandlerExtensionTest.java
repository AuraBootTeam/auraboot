package com.auraboot.framework.plugin.extension;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CommandHandlerExtension} extension point.
 *
 * Validates the contract of the CommandHandlerExtension interface including
 * command execution, support matching, priority ordering, and context passing.
 *
 * Test IDs: C4-01 through C4-06
 *
 * @author AuraBoot Team
 */
@DisplayName("CommandHandlerExtension Unit Tests")
class CommandHandlerExtensionTest {

    // ── Inner test implementations ────────────────────────────────────────

    /**
     * Mock handler that simulates an asset registration command.
     */
    static class AssetRegisterHandler implements CommandHandlerExtension {

        @Override
        public String getCommandType() {
            return "asset:register";
        }

        @Override
        public Object execute(CommandContext context) {
            return Map.of(
                    "status", "registered",
                    "assetCode", context.payload().get("assetCode"),
                    "tenantId", context.tenantId()
            );
        }
    }

    /**
     * Mock handler with configurable priority.
     */
    static class PrioritizedHandler implements CommandHandlerExtension {

        private final String commandType;
        private final int priority;

        PrioritizedHandler(String commandType, int priority) {
            this.commandType = commandType;
            this.priority = priority;
        }

        @Override
        public String getCommandType() {
            return commandType;
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        public Object execute(CommandContext context) {
            return "executed:" + commandType;
        }
    }

    /**
     * Mock handler that always throws an exception.
     */
    static class FailingHandler implements CommandHandlerExtension {

        @Override
        public String getCommandType() {
            return "asset:fail";
        }

        @Override
        public Object execute(CommandContext context) throws Exception {
            throw new IllegalStateException("Command execution failed: " + context.commandType());
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C4-01: Execute asset:register command and verify result")
    void executeAssetRegisterCommand_shouldReturnRegistrationResult() throws Exception {
        // Arrange
        var handler = new AssetRegisterHandler();
        var context = CommandContext.builder()
                .tenantId(1001L)
                .pluginId("asset-plugin")
                .namespace("asset")
                .commandType("asset:register")
                .modelCode("fixed_asset")
                .recordId("rec-001")
                .payload(Map.of("assetCode", "AST-202501-0001", "assetName", "Office Laptop"))
                .settings(Map.of("autoApprove", true))
                .build();

        // Act
        Object result = handler.execute(context);

        // Assert
        assertNotNull(result);
        assertInstanceOf(Map.class, result);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals("registered", resultMap.get("status"));
        assertEquals("AST-202501-0001", resultMap.get("assetCode"));
        assertEquals(1001L, resultMap.get("tenantId"));
    }

    @Test
    @DisplayName("C4-02: supports() returns true for matching command type")
    void supports_matchingCommandType_shouldReturnTrue() {
        // Arrange
        var handler = new AssetRegisterHandler();

        // Act & Assert
        assertTrue(handler.supports("asset:register"));
    }

    @Test
    @DisplayName("C4-03: supports() returns false for non-matching command type")
    void supports_nonMatchingCommandType_shouldReturnFalse() {
        // Arrange
        var handler = new AssetRegisterHandler();

        // Act & Assert
        assertFalse(handler.supports("other:cmd"));
        assertFalse(handler.supports("asset:delete"));
        assertFalse(handler.supports(""));
    }

    @Test
    @DisplayName("C4-04: Priority ordering - higher priority handlers sort first")
    void priorityOrdering_shouldSortByPriorityDescending() {
        // Arrange
        var lowPriority = new PrioritizedHandler("asset:low", 0);
        var medPriority = new PrioritizedHandler("asset:med", 50);
        var highPriority = new PrioritizedHandler("asset:high", 100);

        List<CommandHandlerExtension> handlers = List.of(lowPriority, highPriority, medPriority);

        // Act - sort by priority descending (higher priority first)
        List<CommandHandlerExtension> sorted = handlers.stream()
                .sorted(Comparator.comparingInt(CommandHandlerExtension::getPriority).reversed())
                .toList();

        // Assert
        assertEquals(100, sorted.get(0).getPriority());
        assertEquals(50, sorted.get(1).getPriority());
        assertEquals(0, sorted.get(2).getPriority());
        assertEquals("asset:high", sorted.get(0).getCommandType());
        assertEquals("asset:med", sorted.get(1).getCommandType());
        assertEquals("asset:low", sorted.get(2).getCommandType());
    }

    @Test
    @DisplayName("C4-05: CommandContext preserves all fields correctly")
    void commandContext_allFields_shouldBeAccessible() {
        // Arrange
        Map<String, Object> payload = Map.of("key1", "value1", "key2", 42);
        Map<String, Object> settings = Map.of("setting1", true);

        // Act
        var context = CommandContext.builder()
                .tenantId(2001L)
                .pluginId("test-plugin-001")
                .namespace("test-ns")
                .commandType("test:action")
                .modelCode("test_model")
                .recordId("rec-999")
                .payload(payload)
                .settings(settings)
                .build();

        // Assert
        assertEquals(2001L, context.tenantId());
        assertEquals("test-plugin-001", context.pluginId());
        assertEquals("test-ns", context.namespace());
        assertEquals("test:action", context.commandType());
        assertEquals("test_model", context.modelCode());
        assertEquals("rec-999", context.recordId());
        assertEquals(payload, context.payload());
        assertEquals("value1", context.payload().get("key1"));
        assertEquals(42, context.payload().get("key2"));
        assertEquals(settings, context.settings());
        assertTrue((Boolean) context.settings().get("setting1"));
    }

    @Test
    @DisplayName("C4-06: Handler exception propagates correctly")
    void execute_handlerThrows_shouldPropagateException() {
        // Arrange
        var handler = new FailingHandler();
        var context = CommandContext.builder()
                .tenantId(1L)
                .pluginId("fail-plugin")
                .namespace("asset")
                .commandType("asset:fail")
                .modelCode("model")
                .recordId("rec-1")
                .payload(Map.of())
                .settings(Map.of())
                .build();

        // Act & Assert
        var exception = assertThrows(IllegalStateException.class, () -> handler.execute(context));
        assertTrue(exception.getMessage().contains("Command execution failed"));
        assertTrue(exception.getMessage().contains("asset:fail"));
    }
}
