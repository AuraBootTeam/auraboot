package com.auraboot.framework.meta.synthesized;

import com.auraboot.framework.meta.dto.DynamicBatchResponse;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.CommandInvocationAuthorizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Synthesized-command handler for the dynamic CRUD REST surface. When
 * {@code aura.command.synthesized-dynamic-writes=true}, the six
 * {@code dynamic.*} write endpoints execute through the command pipeline with
 * this handler carrying the persistence, so SoD, idempotency, state guards,
 * events and pipeline auditing all apply.
 * <p>
 * Invocation authorization uses the command's persisted model binding and its
 * registered operation handler. Payload model values are assertions, never an
 * alternative authority. The permission service retains normal wildcard and
 * tenant/member policy semantics.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicWriteCommandHandler implements CommandHandlerExtension, CommandInvocationAuthorizer {

    public static final String HANDLER_CODE = "dynamic.write";
    public static final String CREATE = "dynamic.create";
    public static final String UPDATE = "dynamic.update";
    public static final String DELETE = "dynamic.delete";
    public static final String BATCH_CREATE = "dynamic.batch_create";
    public static final String BATCH_UPDATE = "dynamic.batch_update";
    public static final String BATCH_DELETE = "dynamic.batch_delete";
    static final Set<String> ALL = Set.of(CREATE, UPDATE, DELETE, BATCH_CREATE, BATCH_UPDATE, BATCH_DELETE);

    private final DynamicDataService dynamicDataService;
    private final UserPermissionService userPermissionService;

    @Override
    public String getCommandType() {
        return HANDLER_CODE;
    }

    @Override
    public boolean supports(String commandType) {
        return ALL.contains(commandType);
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return ALL;
    }

    /**
     * The handler owns its persistence via {@link DynamicDataService}; the
     * generic DSL field-map / state-transition persistence must not run first.
     */
    @Override
    public boolean requiresDslPersistence(String commandType, Map<String, Object> execConfig,
                                          com.auraboot.framework.meta.dto.CommandExecuteRequest request) {
        return false;
    }

    @Override
    public Object execute(CommandContext context) {
        String modelCode = context.modelCode();
        if (modelCode == null || !modelCode.equals(str(context.payload(), "modelCode"))) {
            throw new IllegalArgumentException("Dynamic write model differs from its command definition");
        }
        Map<String, Object> data = map(context.payload(), "data");
        List<Map<String, Object>> dataList = mapList(context.payload(), "dataList");
        List<String> recordPids = strList(context.payload(), "recordPids");
        String recordPid = context.recordId();
        if ((UPDATE.equals(context.commandType()) || DELETE.equals(context.commandType()))
                && (recordPid == null || !recordPid.equals(str(context.payload(), "recordPid")))) {
            throw new IllegalArgumentException("Dynamic write requires its authorized target record");
        }
        Long expectedVersion = context.payload().get("expectedVersion") instanceof Number n ? n.longValue() : null;
        if (expectedVersion != null && data != null) {
            data.putIfAbsent("_expectedVersion", expectedVersion);
        }

        return switch (context.commandType()) {
            case CREATE -> dynamicDataService.create(modelCode, data);
            case UPDATE -> dynamicDataService.update(modelCode, recordPid, data);
            case DELETE -> {
                dynamicDataService.delete(modelCode, recordPid);
                yield null;
            }
            case BATCH_CREATE -> dynamicDataService.batchCreate(modelCode, dataList);
            case BATCH_UPDATE -> dynamicDataService.batchUpdate(modelCode, dataList);
            case BATCH_DELETE -> {
                dynamicDataService.batchDelete(modelCode, recordPids);
                yield null;
            }
            default -> throw new IllegalArgumentException("Unsupported dynamic write command: " + context.commandType());
        };
    }

    @Override
    public boolean authorizeInvocation(InvocationContext context) {
        String modelCode = context.modelCode();
        if (modelCode == null || modelCode.isBlank()
                || !modelCode.equals(str(context.payload(), "modelCode"))
                || !ALL.contains(context.handlerCode())) {
            return false;
        }
        if ((UPDATE.equals(context.handlerCode()) || DELETE.equals(context.handlerCode()))
                && (context.targetRecordPid() == null || context.targetRecordPid().isBlank()
                    || !context.targetRecordPid().equals(str(context.payload(), "recordPid")))) return false;
        // Fail-closed: the permission service keys on user id, resolved from the
        // authenticated thread context; no context or lookup failure => deny.
        Long userId;
        try {
            userId = com.auraboot.framework.application.tenant.MetaContext.getCurrentUserId();
        } catch (IllegalStateException noContext) {
            return false;
        }
        if (userId == null) {
            return false;
        }
        String permission = "model." + modelCode + "." + permissionSuffix(context.handlerCode());
        return userPermissionService.hasPermission(userId, permission);
    }

    static String permissionSuffix(String commandCode) {
        return switch (commandCode) {
            case CREATE, BATCH_CREATE -> "create";
            case UPDATE, BATCH_UPDATE -> "update";
            case DELETE, BATCH_DELETE -> "delete";
            default -> throw new IllegalArgumentException("Unsupported dynamic write operation: " + commandCode);
        };
    }

    private static String str(Map<String, Object> payload, String key) {
        return payload.get(key) instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> payload, String key) {
        return payload.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Map<String, Object> payload, String key) {
        return payload.get(key) instanceof List<?> l ? (List<Map<String, Object>>) l : null;
    }

    private static List<String> strList(Map<String, Object> payload, String key) {
        return payload.get(key) instanceof List<?> l
                ? l.stream().map(String::valueOf).toList()
                : null;
    }
}
