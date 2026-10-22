package com.auraboot.framework.meta.service;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Test-specific CommandHandler implementation for integration testing.
 * Provides configurable behavior to test different HANDLER phase scenarios.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Component("testCommandHandler")
public class TestCommandHandler implements CommandHandler {

    /**
     * Flag to control whether handler should throw an exception.
     */
    public static volatile boolean shouldThrow = false;

    /**
     * Custom exception message when shouldThrow is true.
     */
    public static volatile String exceptionMessage = "Test handler exception";

    /**
     * Custom result to return from handler execution.
     * If null, returns default result.
     */
    public static volatile Map<String, Object> customResult = null;

    /**
     * Counter to track how many times the handler was executed.
     */
    public static volatile int executionCount = 0;

    /**
     * Last context received by the handler.
     */
    public static volatile CommandHandlerContext lastContext = null;

    @Override
    public String getHandlerName() {
        return "testCommandHandler";
    }

    @Override
    public Map<String, Object> execute(CommandHandlerContext context) {
        executionCount++;
        lastContext = context;

        if (shouldThrow) {
            throw new RuntimeException(exceptionMessage);
        }

        if (customResult != null) {
            return new HashMap<>(customResult);
        }

        // Default result
        Map<String, Object> result = new HashMap<>();
        result.put("handlerExecuted", true);
        result.put("handlerName", getHandlerName());
        result.put("commandCode", context.getCommandCode());
        result.put("executionCount", executionCount);
        return result;
    }

    /**
     * Reset all static state for test isolation.
     * Call this in @BeforeEach or @AfterEach.
     */
    public static void reset() {
        shouldThrow = false;
        exceptionMessage = "Test handler exception";
        customResult = null;
        executionCount = 0;
        lastContext = null;
    }
}
