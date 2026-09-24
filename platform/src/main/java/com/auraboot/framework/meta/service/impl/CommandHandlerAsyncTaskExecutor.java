package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.meta.service.AsyncTaskExecutor;
import com.auraboot.framework.meta.service.AsyncTaskFailureClassifier;
import com.auraboot.framework.meta.service.AsyncTaskResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.RecordShareAccessor;
import com.auraboot.framework.plugin.extension.TenantProjectionAccessor;
import com.auraboot.framework.plugin.pf4j.BackgroundDataAccessorImpl;
import com.auraboot.framework.plugin.pf4j.BiTemporalAccessorImpl;
import com.auraboot.framework.plugin.pf4j.DynamicDataAccessorImpl;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.plugin.pf4j.FileAccessorImpl;
import com.auraboot.framework.plugin.pf4j.IndependentTransactionAccessorImpl;
import com.auraboot.framework.plugin.pf4j.AsyncTaskAccessorImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import com.auraboot.framework.plugin.pf4j.LlmProviderAccessorImpl;
import com.auraboot.framework.plugin.pf4j.TenantProjectionAccessorImpl;
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
    @Autowired(required = false)
    private RecordShareAccessor recordShareAccessor;
    @Autowired(required = false)
    private PlatformTransactionManager platformTransactionManager;
    @Autowired(required = false)
    private ObjectProvider<AsyncTaskServiceImpl> asyncTaskServiceProvider;
    @Autowired(required = false)
    private CommandEffectExecutor commandEffectExecutor;

    @Autowired
    private com.auraboot.framework.tenant.service.TenantMemberService tenantMemberService;
    @Autowired
    private com.auraboot.framework.rbac.service.UserRoleService userRoleService;

    @Override
    public String getTaskType() {
        return TASK_TYPE;
    }

    @Override
    public AsyncTaskResult execute(JsonNode inputParams, ProgressCallback callback) {
        if (inputParams == null) {
            return AsyncTaskResult.nonRetryableFailure("Missing input params for command-handler task");
        }
        String handlerCode = text(inputParams, "handlerCode");
        String commandCode = text(inputParams, "commandCode");
        if (handlerCode == null || handlerCode.isBlank()) {
            return AsyncTaskResult.nonRetryableFailure("command-handler task missing handlerCode");
        }
        Long tenantId = longValue(inputParams, "tenantId");
        Long userId = longValue(inputParams, "userId");
        String currentUserPid = text(inputParams, "currentUserPid");
        String modelCode = text(inputParams, "modelCode");
        String recordPid = text(inputParams, "recordPid");
        String clientRequestId = text(inputParams, "clientRequestId");
        Long commandExpectedVersion = longValue(inputParams, "commandExpectedVersion");
        Map<String, Object> payload = mapValue(inputParams.get("payload"));
        Map<String, Object> handlerParams = mapValue(inputParams.get("handlerParams"));
        long startedAt = System.nanoTime();

        Optional<CommandHandlerExtension> pluginHandler = extensionRegistry.getCommandHandler(handlerCode);
        if (pluginHandler.isEmpty()) {
            return AsyncTaskResult.nonRetryableFailure(
                    "No plugin command handler found for: " + handlerCode);
        }
        CommandHandlerExtension handler = pluginHandler.get();

        callback.report(1, "Starting " + (commandCode != null ? commandCode : handlerCode));

        if (tenantId == null || userId == null) {
            return AsyncTaskResult.nonRetryableFailure("Command task requires persisted tenant and user identity");
        }
        MetaContext.Snapshot previousContext = MetaContext.snapshot();
        // Recovery is dispatched by a scheduler with no request-thread context. Always
        // establish persisted task identity rather than relying on TaskDecorator inheritance.
        MetaContext.clear();
        MetaContext.setContext(tenantId, userId, currentUserPid, String.valueOf(userId));
        DynamicDataQueryScope queryScope = DynamicDataQueryScope.open();
        try {
            // Resolve current membership/roles from IAM, never trust a stale role snapshot
            // from the enqueue request or permissions supplied by the task payload.
            var member = tenantMemberService.findByTenantIdAndUserId(tenantId, userId);
            if (member == null || member.getId() == null || member.getId() <= 0
                    || !tenantId.equals(member.getTenantId()) || !userId.equals(member.getUserId())
                    || Boolean.TRUE.equals(member.getDeletedFlag())
                    || !com.auraboot.framework.common.constant.StatusConstants.ACTIVE.equalsIgnoreCase(member.getStatus())) {
                return AsyncTaskResult.nonRetryableFailure("Command task initiator is not an active tenant member");
            }
            var roles = userRoleService.getRoleIdsByMemberIdAndTenantId(member.getId(), tenantId);
            MetaContext.setContext(tenantId, userId, currentUserPid, String.valueOf(userId),
                    roles == null ? java.util.Set.of() : new java.util.HashSet<>(roles));
            MetaContext.setMemberId(member.getId());
            String namespace = handlerCode.contains(":") ? handlerCode.split(":")[0] : null;
            Map<String, Object> pluginSettings = new HashMap<>(handlerParams);
            pluginSettings.put("__commandCode", commandCode != null ? commandCode : handlerCode);
            pluginSettings.put("__handlerCode", handlerCode);
            if (userId != null) {
                pluginSettings.put("__currentUser", userId.toString());
            }
            if (currentUserPid != null && !currentUserPid.isBlank()) {
                pluginSettings.put(CommandHandlerExtension.CURRENT_USER_PID_KEY,
                        currentUserPid.trim());
            }
            if (clientRequestId != null && !clientRequestId.isBlank()) {
                pluginSettings.put(CommandHandlerExtension.CLIENT_REQUEST_ID_KEY,
                        clientRequestId.trim());
            }
            if (commandExpectedVersion != null) {
                pluginSettings.put(CommandHandlerExtension.EXPECTED_VERSION_KEY,
                        commandExpectedVersion);
            }
            pluginSettings.put("__dataAccessor", new DynamicDataAccessorImpl(dynamicDataService));
            if (Boolean.TRUE.equals(pluginSettings.get(TenantProjectionAccessor.OPT_IN_HANDLER_PARAM))) {
                pluginSettings.put(CommandHandlerExtension.TENANT_PROJECTION_ACCESSOR_KEY,
                        new TenantProjectionAccessorImpl(tenantId,
                                new BackgroundDataAccessorImpl(dynamicDataService)));
            }
            // A command launched by an async handler may itself enqueue a follow-up
            // command (for example, attachment upload -> Gerber parse). Resolve the
            // task service lazily to avoid a bean cycle with its executor registry.
            AsyncTaskServiceImpl asyncTaskService = asyncTaskServiceProvider == null
                    ? null : asyncTaskServiceProvider.getIfAvailable();
            if (asyncTaskService != null) {
                pluginSettings.put(CommandHandlerExtension.ASYNC_TASK_ACCESSOR_KEY,
                        new AsyncTaskAccessorImpl(asyncTaskService, objectMapper, tenantId, userId));
            }
            if (platformTransactionManager != null) {
                pluginSettings.put(CommandHandlerExtension.INDEPENDENT_TRANSACTION_ACCESSOR_KEY,
                        new IndependentTransactionAccessorImpl(platformTransactionManager, dynamicDataService));
            } else if (inputParams.path("resumeOnRestart").asBoolean(false)) {
                return AsyncTaskResult.nonRetryableFailure("Resumable command requires checkpoint transactions");
            }
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
            if (recordShareAccessor != null) {
                pluginSettings.put(RecordShareAccessor.SETTINGS_KEY, recordShareAccessor);
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
            java.util.function.Supplier<Object> invocation = () -> {
                try {
                    return handler.execute(pluginContext);
                } catch (Exception ex) {
                    throw new CommandHandlerInvocationException(ex);
                }
            };
            if (commandAuthority != null) {
                java.util.function.Supplier<Object> delegate = invocation;
                invocation = () -> MetaContext.runWithCommandAuthority(commandAuthority, delegate);
            }
            String commandPermitScope = text(inputParams, "commandPermitScope");
            java.util.function.Supplier<Object> permittedInvocation = invocation;
            java.util.function.Supplier<Object> commandScopedInvocation = commandCode == null
                    ? permittedInvocation
                    : () -> MetaContext.runWithAuthorizedCommandCode(commandCode, permittedInvocation);
            Object result = commandPermitScope == null
                    ? permittedInvocation.get()
                    : MetaContext.runWithCommandPermitPlan(
                            commandPermitScope,
                            commandExpectedVersion,
                            modelCode,
                            recordPid,
                            commandScopedInvocation);
            callback.report(100, "Completed");

            JsonNode data = result == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.valueToTree(result);
            recordAsyncAudit(tenantId, userId, commandCode != null ? commandCode : handlerCode, payload,
                    result instanceof Map<?, ?> ? mapValue(data) : Map.of(),
                    true, null, startedAt);
            return AsyncTaskResult.ok(data);
        } catch (CommandHandlerInvocationException wrapped) {
            log.error("Async command handler {} failed", handlerCode, wrapped.getCause());
            Throwable cause = wrapped.getCause();
            recordAsyncAudit(tenantId, userId, commandCode != null ? commandCode : handlerCode, payload,
                    Map.of(), false, cause.getMessage(), startedAt);
            return failureResult(cause);
        } catch (Exception ex) {
            // Async-task boundary: any handler failure (CommandHandlerExtension.execute
            // declares `throws Exception`) must be reported as a task failure result —
            // not swallowed and not rethrown — so the task framework records FAILED with
            // the message. This is a terminal boundary catch, not a self-heal/fallback.
            log.error("Async command handler {} failed", handlerCode, ex);
            recordAsyncAudit(tenantId, userId, commandCode != null ? commandCode : handlerCode, payload,
                    Map.of(), false, ex.getMessage(), startedAt);
            return failureResult(ex);
        } finally {
            queryScope.close();
            MetaContext.clear();
            MetaContext.restore(previousContext);
        }
    }

    private void recordAsyncAudit(Long tenantId, Long userId, String commandCode,
                                  Map<String, Object> payload, Map<String, Object> result,
                                  boolean success, String error, long startedAt) {
        if (commandEffectExecutor == null) return;
        commandEffectExecutor.saveAuditLog(tenantId, commandCode, null, userId,
                payload, result, success, error,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                "async_handler", Map.of());
    }

    private AsyncTaskResult failureResult(Throwable failure) {
        String message = failure.getMessage() != null ? failure.getMessage() : failure.toString();
        return AsyncTaskFailureClassifier.isRetryable(failure)
                ? AsyncTaskResult.retryableFailure(message)
                : AsyncTaskResult.nonRetryableFailure(message);
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
