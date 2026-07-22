package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.AsyncTaskExecutor.ProgressCallback;
import com.auraboot.framework.meta.service.AsyncTaskResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The async command path never re-enters the pipeline: {@code CommandHandlerAsyncTaskExecutor}
 * invokes the handler directly and re-materialises identity from {@code ab_async_task.input_params}.
 * The command boundary's decision would therefore be lost at the thread hand-off — and that is
 * exactly the path production broke on (2026-07-22, {@code async-task-3}): the boundary said yes,
 * then the row the run had just created could not be updated by the run itself.
 *
 * <p>So the verdict travels WITH the task and is rebuilt here. These tests pin that it is rebuilt
 * when present, absent when not, and never left behind on a pooled thread.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Async command handler rebuilds the boundary's authority")
class CommandHandlerAsyncTaskExecutorAuthorityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private ExtensionRegistry extensionRegistry;
    @Mock private DynamicDataService dynamicDataService;

    private final AtomicReference<String> authoritySeenByHandler = new AtomicReference<>();

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("a persisted verdict is rebuilt for the handler")
    void aPersistedVerdictIsRebuilt() {
        CommandHandlerAsyncTaskExecutor executor = executorWithRecordingHandler();

        AsyncTaskResult result = executor.execute(input("qo.price.manage"), noopCallback());

        assertThat(result.isSuccess()).isTrue();
        assertThat(authoritySeenByHandler.get()).isEqualTo("qo.price.manage");
    }

    /**
     * A task submitted by a command that granted nothing must carry nothing. If a missing verdict
     * were treated as permission, every undeclared command would gain authority on the async path.
     */
    @Test
    @DisplayName("no persisted verdict means the handler runs with no authority")
    void noVerdictMeansNoAuthority() {
        CommandHandlerAsyncTaskExecutor executor = executorWithRecordingHandler();

        AsyncTaskResult result = executor.execute(input(null), noopCallback());

        assertThat(result.isSuccess()).isTrue();
        assertThat(authoritySeenByHandler.get()).isNull();
    }

    /** Pooled threads: the authority must not still be standing when the next task arrives. */
    @Test
    @DisplayName("the authority does not survive the task")
    void theAuthorityDoesNotSurviveTheTask() {
        CommandHandlerAsyncTaskExecutor executor = executorWithRecordingHandler();

        executor.execute(input("qo.price.manage"), noopCallback());

        assertThat(MetaContext.hasCommandAuthority()).isFalse();
    }

    /** A failing handler must report the same way with a scope open as without one. */
    @Test
    @DisplayName("a handler failure is still reported as a task failure, and closes the scope")
    void aHandlerFailureIsStillReportedAndClosesTheScope() {
        CommandHandlerExtension handler = new CommandHandlerExtension() {
            @Override public String getCommandType() { return "qo_quote_common:batch_source_prices"; }
            @Override public Object execute(CommandContext context) throws Exception {
                throw new IllegalStateException("provider timeout");
            }
        };
        when(extensionRegistry.getCommandHandler("qo_quote_common:batch_source_prices"))
                .thenReturn(Optional.of(handler));
        CommandHandlerAsyncTaskExecutor executor =
                new CommandHandlerAsyncTaskExecutor(extensionRegistry, objectMapper, dynamicDataService);

        AsyncTaskResult result = executor.execute(input("qo.price.manage"), noopCallback());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("provider timeout");
        assertThat(MetaContext.hasCommandAuthority()).isFalse();
    }

    private CommandHandlerAsyncTaskExecutor executorWithRecordingHandler() {
        CommandHandlerExtension handler = new CommandHandlerExtension() {
            @Override public String getCommandType() { return "qo_quote_common:batch_source_prices"; }
            @Override public Object execute(CommandContext context) {
                authoritySeenByHandler.set(MetaContext.getCommandAuthority());
                return Map.of("ok", true);
            }
        };
        when(extensionRegistry.getCommandHandler("qo_quote_common:batch_source_prices"))
                .thenReturn(Optional.of(handler));
        return new CommandHandlerAsyncTaskExecutor(extensionRegistry, objectMapper, dynamicDataService);
    }

    private ObjectNode input(String commandAuthority) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("handlerCode", "qo_quote_common:batch_source_prices");
        input.put("commandCode", "qo_quote_common:batch_source_prices");
        input.put("tenantId", 1L);
        input.put("userId", 42L);
        input.put("modelCode", "qo_quote_common");
        input.put("recordPid", "Q1");
        input.set("payload", objectMapper.createObjectNode());
        input.set("handlerParams", objectMapper.createObjectNode());
        if (commandAuthority != null) {
            input.put("commandAuthority", commandAuthority);
        }
        return input;
    }

    private ProgressCallback noopCallback() {
        return (percent, message) -> { };
    }
}
