package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.integration.InstalledIntegrationBinding;
import com.auraboot.framework.plugin.extension.integration.InstalledIntegrationEvent;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Host-only, lexical command identity. Not request data and not an independent data permit. */
public final class InstalledIntegrationExecution {
    private record Invocation(String command, String config, InstalledIntegrationBinding binding, boolean query, Object handler, String target, InstalledIntegrationEvent event) {}
    private static final ThreadLocal<Invocation> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Invocation> VERIFIED = new ThreadLocal<>();
    private static final ThreadLocal<Invocation> VERIFIED_HANDLER = new ThreadLocal<>();
    private InstalledIntegrationExecution() {}

    static <T> T run(String command, String config, InstalledIntegrationBinding binding, boolean query, Object handler, Supplier<T> work) {
        return run(command,config,binding,query,handler,null,work);
    }

    static <T> T run(String command, String config, InstalledIntegrationBinding binding, boolean query, Object handler,
                     String target, Supplier<T> work) {
        return run(command,config,binding,query,handler,target,null,work);
    }

    static <T> T run(String command, String config, InstalledIntegrationBinding binding, boolean query, Object handler,
                     String target, InstalledIntegrationEvent event, Supplier<T> work) {
        if (CURRENT.get() != null) throw new SecurityException("Nested installed integration invocation denied");
        CURRENT.set(new Invocation(command, config, binding, query, handler, target, event));
        try (var authority = InstalledMachineAuthority.open(binding, config, query)) { return work.get(); }
        finally { VERIFIED_HANDLER.remove(); VERIFIED.remove(); CURRENT.remove(); }
    }

    public static void verify(String command, Long tenant, String config, Map<String,Object> params) {
        Invocation value = CURRENT.get();
        boolean declared = params != null && params.containsKey("installedIntegration");
        if (value == null) {
            if (declared) throw new SecurityException("Installed command requires authenticated installation binding");
            return;
        }
        if (!declared || !value.command().equals(command) || !Objects.equals(tenant, value.binding().tenantId())
                || !value.config().equals(config)) throw new SecurityException("Installed command identity changed");
        VERIFIED.set(value);
    }

    public static void verifyTarget(String command, String target, Map<String,Object> payload) {
        Invocation value = CURRENT.get();
        if (value == null) return;
        if (!value.command().equals(command) || !Objects.equals(value.target(),target)
                || (target != null && (payload == null || !payload.equals(Map.of("requestPid",target)))))
            throw new SecurityException("Installed command target changed");
    }

    public static void install(String command, Long tenant, Map<String,Object> settings) {
        settings.remove(InstalledIntegrationBinding.SETTINGS_KEY);
        settings.remove(InstalledIntegrationEvent.SETTINGS_KEY);
        Invocation value = CURRENT.get();
        if (value == null) return;
        if (!value.command().equals(command) || !Objects.equals(tenant, value.binding().tenantId()))
            throw new SecurityException("Installed handler identity changed");
        settings.put(InstalledIntegrationBinding.SETTINGS_KEY, value.binding());
        if(value.event()!=null)settings.put(InstalledIntegrationEvent.SETTINGS_KEY,value.event());
    }

    public static void verifyHandler(String command, Object handler, java.util.List<?> secondaryHandlers) {
        Invocation value = CURRENT.get();
        if (value == null) return;
        if (!value.command().equals(command) || value.handler() == null || value.handler() != handler
                || !secondaryHandlers.isEmpty()) throw new SecurityException("Installed handler owner changed");
        VERIFIED_HANDLER.set(value);
    }

    record WriteProof(Invocation invocation, String rootModel, String rootPid,
            com.auraboot.framework.plugin.extension.CommandHandlerExtension handler) {
        String command() { return invocation.command(); }
        InstalledIntegrationBinding binding() { return invocation.binding(); }
        void verify() {
            if (CURRENT.get()!=invocation || VERIFIED.get()!=invocation || VERIFIED_HANDLER.get()!=invocation
                    || invocation.query() || invocation.target()==null || !invocation.target().equals(rootPid)
                    || invocation.handler()!=handler || !Objects.equals(MetaContext.getCurrentUserId(),0L)
                    || !Objects.equals(MetaContext.getCurrentTenantId(),binding().tenantId())
                    || !("installation:"+binding().installationPid()).equals(MetaContext.getCurrentUserPid())
                    || !command().equals(MetaContext.getAuthorizedCommandCode())
                    || !MetaContext.hasCommandPermitScopeFor(rootModel)
                    || !rootPid.equals(MetaContext.getCommandPermitTargetFor(rootModel)))
                throw new SecurityException("Installed related write requires its verified process invocation");
        }
    }

    public static void installRelatedWriter(Map<String,Object> settings,
            com.auraboot.framework.meta.service.DynamicDataService data,
            com.auraboot.framework.plugin.extension.CommandHandlerExtension handler,
            String rootModel, String rootPid) {
        Object opted=settings.get(InstalledRelatedWriteAccessor.OPT_IN);
        if (opted==null || Boolean.FALSE.equals(opted)) return;
        if (!Boolean.TRUE.equals(opted)) throw new SecurityException("Installed related write policy must be boolean");
        Invocation invocation=CURRENT.get();
        if (invocation==null || handler==null || rootModel==null || rootPid==null
                || !(settings.get(com.auraboot.framework.plugin.extension.CommandHandlerExtension.TENANT_PROJECTION_ACCESSOR_KEY)
                     instanceof com.auraboot.framework.plugin.extension.TenantProjectionAccessor reads)
                || settings.get(InstalledIntegrationBinding.SETTINGS_KEY)!=invocation.binding())
            throw new SecurityException("Installed related write policy requires its process and tenant projection");
        WriteProof proof=new WriteProof(invocation,rootModel,rootPid,handler);
        proof.verify();
        settings.put(com.auraboot.framework.plugin.extension.CommandHandlerExtension.DATA_ACCESSOR_KEY,
                new InstalledRelatedWriteAccessor(data,proof,reads));
    }

    public static void finishSettings(Map<String,Object> settings) {
        if (CURRENT.get() == null) return;
        for (String key : java.util.List.of("__independentTransactionAccessor","__asyncTaskAccessor",
                com.auraboot.framework.plugin.extension.CommandDispatcherAccessor.SETTINGS_KEY,"__tenantRoleAssignmentAccessor","__verifiedPhoneGrantAccessor",
                "__biTemporalAccessor","__aiProviderAccessor","__fileAccessor","__recordShareAccessor",
                com.auraboot.framework.plugin.extension.AnnouncementAcknowledgementAccessor.SETTINGS_KEY,
                com.auraboot.framework.plugin.extension.CommandMembershipLockAccessor.SETTINGS_KEY,
                com.auraboot.framework.plugin.extension.RecordActionScopeAccessor.SETTINGS_KEY)) settings.remove(key);
        if (query()) {
            settings.remove(com.auraboot.framework.plugin.extension.TenantSecretAccessor.SETTINGS_KEY);
            Object data = settings.get(com.auraboot.framework.plugin.extension.CommandHandlerExtension.DATA_ACCESSOR_KEY);
            if (!(data instanceof com.auraboot.framework.plugin.extension.DataAccessor accessor))
                throw new SecurityException("Installed result reader unavailable");
            settings.put(com.auraboot.framework.plugin.extension.CommandHandlerExtension.DATA_ACCESSOR_KEY,
                    InstalledIntegrationReadOnly.wrap(accessor));
        }
    }

    static void verifyDataAuthority() {
        Invocation invocation=CURRENT.get();
        if (invocation==null || VERIFIED.get()!=invocation || VERIFIED_HANDLER.get()!=invocation
                || !Objects.equals(MetaContext.getCurrentTenantId(),invocation.binding().tenantId())
                || !Objects.equals(MetaContext.getCurrentUserId(),0L)
                || !("installation:"+invocation.binding().installationPid()).equals(MetaContext.getCurrentUserPid())
                || !invocation.command().equals(MetaContext.getAuthorizedCommandCode()))
            throw new org.springframework.security.access.AccessDeniedException("Verified installed data invocation required");
    }

    public static boolean active() { return CURRENT.get() != null; }
    public static boolean query() { return CURRENT.get() != null && CURRENT.get().query(); }
}
