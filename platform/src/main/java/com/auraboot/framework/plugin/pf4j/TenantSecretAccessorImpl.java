package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.plugin.extension.TenantSecretAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Explicit metadata scopes restrict trusted handlers to tenant-owned CloudConfig fields. */
public final class TenantSecretAccessorImpl implements TenantSecretAccessor {
    private final Long tenantId;
    private final Set<String> serviceTypes;
    private final Set<String> fields;
    private final CloudConfigService configs;
    private final ObjectMapper json;

    private TenantSecretAccessorImpl(Long tenantId, Set<String> serviceTypes, Set<String> fields,
            CloudConfigService configs, ObjectMapper json) {
        if (tenantId == null || tenantId <= 0) throw new SecurityException("SECRET_TENANT_REQUIRED");
        this.tenantId = tenantId; this.serviceTypes = serviceTypes; this.fields = fields;
        this.configs = Objects.requireNonNull(configs); this.json = Objects.requireNonNull(json);
    }

    /** Called only after command authorization, with server-loaded handler parameters. */
    public static void install(Map<String,Object> settings, Long tenantId,
            CloudConfigService configs, ObjectMapper json) {
        settings.remove(SETTINGS_KEY);
        if (!Boolean.TRUE.equals(settings.get(OPT_IN_HANDLER_PARAM))) return;
        if (configs == null) throw new IllegalStateException("SECRET_STORE_REQUIRED");
        settings.put(SETTINGS_KEY, new TenantSecretAccessorImpl(tenantId,
                scope(settings.get(SERVICE_TYPES_HANDLER_PARAM)), scope(settings.get(FIELDS_HANDLER_PARAM)), configs, json));
    }

    private static Set<String> scope(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 32)
            throw new IllegalArgumentException("SECRET_SCOPE_REQUIRED");
        Set<String> result = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String text) || !valid(text) || !result.add(text))
                throw new IllegalArgumentException("SECRET_SCOPE_INVALID");
        }
        return Set.copyOf(result);
    }

    private static boolean valid(String value) {
        return value != null && value.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}");
    }

    @Override public String readCloudSecret(String serviceType, String providerCode, String field) {
        if (!tenantId.equals(MetaContext.getCurrentTenantId()) || MetaContext.getCurrentUserPid() == null
                || MetaContext.getCurrentUserPid().isBlank()) throw new SecurityException("SECRET_CONTEXT_MISMATCH");
        if (!serviceTypes.contains(serviceType) || !fields.contains(field) || !valid(providerCode))
            throw new SecurityException("SECRET_SCOPE_DENIED");
        var config = configs.getEffectiveConfig(tenantId, serviceType, providerCode);
        if (config == null || !tenantId.equals(config.getTenantId()) || !"tenant".equals(config.getConfigLevel())
                || !Boolean.TRUE.equals(config.getEnabled()) || Boolean.TRUE.equals(config.getDeletedFlag())
                || !serviceType.equals(config.getServiceType()) || !providerCode.equals(config.getProviderCode()))
            throw new SecurityException("SECRET_CONFIG_UNAVAILABLE");
        if (config.getConfig() == null || config.getConfig().isBlank())
            throw new IllegalStateException("SECRET_CONFIG_INVALID");
        JsonNode root;
        try { root = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(config.getConfig()); }
        catch (JsonProcessingException malformed) {
            // Parser diagnostics may contain credential fragments; reject without exposing them.
            throw new IllegalStateException("SECRET_CONFIG_INVALID");
        }
        if (root == null || !root.isObject()) throw new IllegalStateException("SECRET_CONFIG_INVALID");
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().startsWith("ENC:") || value.asText().startsWith("****"))
            throw new IllegalStateException("SECRET_VALUE_UNAVAILABLE");
        return value.asText();
    }
}
