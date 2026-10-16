package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.integration.InstalledIntegrationBinding;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Host-only, lexical command identity. Not request data and not an independent data permit. */
public final class InstalledIntegrationExecution {
    private record Invocation(String command, String config, InstalledIntegrationBinding binding, boolean query, Object handler, String target) {}
    private static final ThreadLocal<Invocation> CURRENT = new ThreadLocal<>();
    private InstalledIntegrationExecution() {}

    static <T> T run(String command, String config, InstalledIntegrationBinding binding, boolean query, Object handler, Supplier<T> work) {
        return run(command,config,binding,query,handler,null,work);
    }

    static <T> T run(String command, String config, InstalledIntegrationBinding binding, boolean query, Object handler,
                     String target, Supplier<T> work) {
        if (CURRENT.get() != null) throw new SecurityException("Nested installed integration invocation denied");
        CURRENT.set(new Invocation(command, config, binding, query, handler, target));
        try { return work.get(); } finally { CURRENT.remove(); }
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
        Invocation value = CURRENT.get();
        if (value == null) return;
        if (!value.command().equals(command) || !Objects.equals(tenant, value.binding().tenantId()))
            throw new SecurityException("Installed handler identity changed");
        settings.put(InstalledIntegrationBinding.SETTINGS_KEY, value.binding());
    }

    public static void verifyHandler(String command, Object handler, java.util.List<?> secondaryHandlers) {
        Invocation value = CURRENT.get();
        if (value == null) return;
        if (!value.command().equals(command) || value.handler() == null || value.handler() != handler
                || !secondaryHandlers.isEmpty()) throw new SecurityException("Installed handler owner changed");
    }

    public static void finishSettings(Map<String,Object> settings) {
        if (CURRENT.get() == null) return;
        for (String key : java.util.List.of("__independentTransactionAccessor","__asyncTaskAccessor",
                com.auraboot.framework.plugin.extension.CommandDispatcherAccessor.SETTINGS_KEY,"__tenantRoleAssignmentAccessor","__verifiedPhoneGrantAccessor",
                "__biTemporalAccessor","__aiProviderAccessor","__fileAccessor","__recordShareAccessor")) settings.remove(key);
        if (query()) {
            settings.remove(com.auraboot.framework.plugin.extension.TenantSecretAccessor.SETTINGS_KEY);
            Object data = settings.get(com.auraboot.framework.plugin.extension.CommandHandlerExtension.DATA_ACCESSOR_KEY);
            if (!(data instanceof com.auraboot.framework.plugin.extension.DataAccessor accessor))
                throw new SecurityException("Installed result reader unavailable");
            settings.put(com.auraboot.framework.plugin.extension.CommandHandlerExtension.DATA_ACCESSOR_KEY,
                    InstalledIntegrationReadOnly.wrap(accessor));
        }
    }

    public static boolean active() { return CURRENT.get() != null; }
    public static boolean query() { return CURRENT.get() != null && CURRENT.get().query(); }
}
