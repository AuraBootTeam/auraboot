package com.auraboot.framework.meta.security;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.service.DomainPermissionPolicy;
import org.springframework.security.access.AccessDeniedException;

import java.util.*;

/** Carries one admitted document use case into its immutable platform artifact projections. */
public final class DocumentArtifactCommandScope {
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private DocumentArtifactCommandScope() { }

    @FunctionalInterface public interface Operation<T> { T get() throws Exception; }

    public static <T> T within(CommandPipelineContext context, Operation<T> operation) throws Exception {
        Object params = context.getExecConfig() == null ? null : context.getExecConfig().get("handlerParams");
        if (!(params instanceof Map<?, ?> values) || !values.containsKey("documentDefinition")) return withoutScope(operation);
        if (!(values.get("documentDefinition") instanceof Map<?, ?> definition)) throw denied();
        // Unrelated ordinary/machine executions retain their existing authorization path.
        String rootModel = context.getCommand() == null ? null : context.getCommand().getModelCode();
        if (!MetaContext.exists() || MetaContext.getCurrentMemberId() == null || context.getRequest() == null
                || context.getAuthorizationVerdict() == null || !context.getAuthorizationVerdict().isAuthorized()
                || !Boolean.TRUE.equals(context.getTargetRecordReadable()) || rootModel == null
                || !MetaContext.isCommandPermitBoundTo(rootModel)) return withoutScope(operation);
        Frame frame = new Frame(context, definition);
        Frame previous = CURRENT.get();
        CURRENT.set(frame);
        try { return operation.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }

    private static <T> T withoutScope(Operation<T> operation) throws Exception {
        Frame previous = CURRENT.get();
        CURRENT.remove();
        try { return operation.get(); }
        finally { if (previous != null) CURRENT.set(previous); }
    }

    /** Only create of the current definition, format and exact admitted source can borrow authority. */
    public static String authorityFor(String model, Map<String, Object> record, String action) {
        Frame frame = CURRENT.get();
        return frame != null && frame.matches(model, record, action) ? frame.authority : null;
    }

    public static boolean permits(Permission permission, String model, Map<String, Object> record, String action) {
        Frame frame = CURRENT.get();
        return frame != null && permission != null && Set.of("write", "manage").contains(Objects.requireNonNullElse(permission.getAction(), ""))
                && Objects.equals(permission.getCode(), frame.authority)
                && DomainPermissionPolicy.permits(permission, frame.rootModel, "update")
                && frame.matches(model, record, action);
    }

    private static final class Frame {
        final Long tenant = MetaContext.getCurrentTenantId(), user = MetaContext.getCurrentUserId(), member = MetaContext.getCurrentMemberId();
        final String command, authority, rootModel, rootPid, definitionCode, definitionVersion;
        final Set<String> outputs;
        final Map<String, String> artifacts = new HashMap<>();

        Frame(CommandPipelineContext context, Map<?, ?> definition) {
            if (!MetaContext.exists() || tenant == null || user == null || member == null
                    || context.getCommand() == null || context.getRequest() == null
                    || context.getAuthorizationVerdict() == null || !context.getAuthorizationVerdict().isAuthorized()
                    || !Boolean.TRUE.equals(context.getTargetRecordReadable())
                    || !Objects.equals(context.getTenantId(), tenant) || !Objects.equals(context.getUserId(), user)) throw denied();
            command = text(context.getCommandCode());
            authority = text(context.getAuthorizationVerdict().permissionCode());
            rootModel = text(context.getCommand().getModelCode());
            rootPid = text(context.getRequest().getTargetRecordId());
            definitionCode = text(definition.get("code"));
            definitionVersion = text(definition.get("version"));
            String dataset = text(definition.get("dataset"));
            if (rootModel == null || rootPid == null || rootPid.isBlank()
                    || !dataset.startsWith(rootModel + ":") || !"immutable-artifact".equals(definition.get("versionPolicy"))
                    || !(definition.get("outputs") instanceof Collection<?> declared) || declared.isEmpty() || declared.size() > 8)
                throw denied();
            var formats = new HashSet<String>();
            for (Object output : declared) if (!formats.add(text(output))) throw denied();
            outputs = Set.copyOf(formats);
            if (!current()) throw denied();
        }

        boolean current() {
            return MetaContext.exists() && Objects.equals(tenant, MetaContext.getCurrentTenantId()) && Objects.equals(user, MetaContext.getCurrentUserId())
                    && Objects.equals(member, MetaContext.getCurrentMemberId())
                    && Objects.equals(command, MetaContext.getAuthorizedCommandCode())
                    && Objects.equals(authority, MetaContext.getCommandAuthority())
                    && MetaContext.isCommandPermitBoundTo(rootModel)
                    && Objects.equals(rootPid, MetaContext.getCommandPermitTargetFor(rootModel))
                    && Objects.equals(rootPid, MetaContext.getCommandAggregateId());
        }

        boolean matches(String model, Map<String, Object> row, String action) {
            if (!current() || !"ab_document_artifact".equals(model) || !"create".equals(action) || row == null
                    || !Objects.equals(tenant, row.get("tenant_id")) || !Objects.equals(user, row.get("created_by"))
                    || !Objects.equals(rootModel, row.get("ab_da_source_model"))
                    || !Objects.equals(rootPid, row.get("ab_da_source_record_pid"))
                    || !Objects.equals(definitionCode, row.get("ab_da_definition_code"))
                    || !Objects.equals(definitionVersion, row.get("ab_da_definition_version"))
                    || !(row.get("ab_da_format") instanceof String format) || !outputs.contains(format)
                    || !(row.get("pid") instanceof String pid) || pid.isBlank()) return false;
            // Re-evaluating the same physical insert is valid; another artifact for the same format is not.
            String previous = artifacts.putIfAbsent(format, pid);
            return previous == null || previous.equals(pid);
        }
    }
    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 256 || !text.equals(text.strip())) throw denied();
        return text;
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Document artifact context does not match the admitted use case"); }
}
