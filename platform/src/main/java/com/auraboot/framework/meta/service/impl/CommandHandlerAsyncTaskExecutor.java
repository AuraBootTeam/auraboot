package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.meta.service.AsyncTaskExecutor;
import com.auraboot.framework.meta.service.AsyncTaskResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.BiTemporalAccessorImpl;
import com.auraboot.framework.plugin.pf4j.DynamicDataAccessorImpl;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.plugin.pf4j.FileAccessorImpl;
import com.auraboot.framework.plugin.pf4j.LlmProviderAccessorImpl;
import com.auraboot.module.bitemporal.service.BiTemporalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Async executor that runs a plugin {@link CommandHandlerExtension} off the
 * request thread, so long-running command handlers (e.g. bulk Excel imports)
 * do not block the HTTP request and trip the BFF proxy timeout (502).
 *
 * <p>Activated when a command declares {@code handlerParams.async: true}; the
 * {@code HandlerPhase} submits a task of type {@code command-handler} instead of
 * invoking the handler inline (see {@code HandlerPhase}).</p>
 *
 * <p>The plugin {@link CommandHandlerExtension.CommandContext} cannot be
 * persisted, so it is rebuilt here from the JSON-serializable input params using
 * the same platform beans {@code HandlerPhase} uses for the synchronous path:</p>
 * <pre>
 * {
 *   "handlerCode": "bom:import_material_library",
 *   "commandCode": "bom:import_material_library",
 *   "tenantId": 123,
 *   "userId": 45,
 *   "modelCode": "bom_material_master",
 *   "recordPid": null,
 *   "payload": { "source_file_id": "01K..." },
 *   "handlerParams": { "async": true }
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandHandlerAsyncTaskExecutor implements AsyncTaskExecutor {

    public static final String TASK_TYPE = "command-handler";

    private final ExtensionRegistry extensionRegistry;
    private final ObjectMapper objectMapper;
    private final DynamicDataService dynamicDataService;

    @Autowired(required = false)
    private BiTemporalService biTemporalService;
    @Autowired(required = false)
    private LlmProviderFactory llmProviderFactory;
    @Autowired(required = false)
    private FileService fileService;
    @Autowired(required = false)
    private StorageProvider storageProvider;

    @Override
    public String getTaskType() {
        return TASK_TYPE;
    }

    @Override
    public AsyncTaskResult execute(JsonNode inputParams, ProgressCallback callback) {
        if (inputParams == null) {
            return AsyncTaskResult.fail("Missing input params for command-handler task");
        }
        String handlerCode = text(inputParams, "handlerCode");
        String commandCode = text(inputParams, "commandCode");
        if (handlerCode == null || handlerCode.isBlank()) {
            return AsyncTaskResult.fail("command-handler task missing handlerCode");
        }
        Long tenantId = longValue(inputParams, "tenantId");
        Long userId = longValue(inputParams, "userId");
        String modelCode = text(inputParams, "modelCode");
        String recordPid = text(inputParams, "recordPid");
        Map<String, Object> payload = mapValue(inputParams.get("payload"));
        Map<String, Object> handlerParams = mapValue(inputParams.get("handlerParams"));

        Optional<CommandHandlerExtension> pluginHandler = extensionRegistry.getCommandHandler(handlerCode);
        if (pluginHandler.isEmpty()) {
            return AsyncTaskResult.fail("No plugin command handler found for: " + handlerCode);
        }
        CommandHandlerExtension handler = pluginHandler.get();

        callback.report(1, "Starting " + (commandCode != null ? commandCode : handlerCode));

        DynamicDataQueryScope queryScope = DynamicDataQueryScope.open();
        try {
            String namespace = handlerCode.contains(":") ? handlerCode.split(":")[0] : null;
            Map<String, Object> pluginSettings = new HashMap<>(handlerParams);
            pluginSettings.put("__commandCode", commandCode != null ? commandCode : handlerCode);
            pluginSettings.put("__handlerCode", handlerCode);
            pluginSettings.put("__dataAccessor", new DynamicDataAccessorImpl(dynamicDataService));
            final ProgressCallback cb = callback;
            pluginSettings.put("__progressReporter",
                    (java.util.function.BiConsumer<Integer, String>)
                            (pct, msg) -> cb.report(pct == null ? 0 : pct, msg));
            if (biTemporalService != null) {
                pluginSettings.put("__biTemporalAccessor",
                        new BiTemporalAccessorImpl(biTemporalService, objectMapper));
            }
            if (llmProviderFactory != null) {
                pluginSettings.put(CommandHandlerExtension.AI_PROVIDER_ACCESSOR_KEY,
                        new LlmProviderAccessorImpl(llmProviderFactory, objectMapper, tenantId));
            }
            if (fileService != null && storageProvider != null) {
                pluginSettings.put(CommandHandlerExtension.FILE_ACCESSOR_KEY,
                        new FileAccessorImpl(fileService, storageProvider, userId));
            }

            CommandHandlerExtension.CommandContext pluginContext =
                    CommandHandlerExtension.CommandContext.builder()
                            .tenantId(tenantId)
                            .namespace(namespace)
                            .commandType(handlerCode)
                            .modelCode(modelCode)
                            .recordId(recordPid)
                            .payload(payload)
                            .settings(pluginSettings)
                            .dryRun(false)
                            .build();

            // Re-establish the authority the command boundary granted on the request thread. This
            // path never re-enters the pipeline, so without this the handler runs with none at all —
            // which is precisely where production broke: the boundary said yes, and then the row the
            // run had just created could not be updated by the run itself.
            // The verdict travels WITH the task rather than riding a thread-local across the
            // hand-off, so what background work carries is a decision that can be named, not an
            // inherited bypass.
            String commandAuthority = text(inputParams, "commandAuthority");
            Object result = commandAuthority == null
                    ? handler.execute(pluginContext)
                    : MetaContext.runWithCommandAuthority(commandAuthority, () -> {
                        try {
                            return handler.execute(pluginContext);
                        } catch (Exception ex) {
                            throw new CommandHandlerInvocationException(ex);
                        }
                    });
            callback.report(100, "Completed");

            JsonNode data = result == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.valueToTree(result);
            return AsyncTaskResult.ok(data);
        } catch (CommandHandlerInvocationException wrapped) {
            log.error("Async command handler {} failed", handlerCode, wrapped.getCause());
            Throwable cause = wrapped.getCause();
            return AsyncTaskResult.fail(cause.getMessage() != null ? cause.getMessage() : cause.toString());
        } catch (Exception ex) {
            // Async-task boundary: any handler failure (CommandHandlerExtension.execute
            // declares `throws Exception`) must be reported as a task failure result —
            // not swallowed and not rethrown — so the task framework records FAILED with
            // the message. This is a terminal boundary catch, not a self-heal/fallback.
            log.error("Async command handler {} failed", handlerCode, ex);
            return AsyncTaskResult.fail(ex.getMessage() != null ? ex.getMessage() : ex.toString());
        } finally {
            queryScope.close();
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asLong();
    }

    /**
     * Carries a handler's checked exception out of the authority scope's Supplier. Unwrapped and
     * reported as a task failure exactly like the direct path — the scope must not change how a
     * handler failure surfaces.
     */
    private static final class CommandHandlerInvocationException extends RuntimeException {
        CommandHandlerInvocationException(Throwable cause) {
            super(cause);
        }
    }

    private Map<String, Object> mapValue(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return new HashMap<>();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }
}
