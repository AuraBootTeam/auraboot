package com.auraboot.framework.plugin.extension;

/** Host-owned, tenant-bound secret reader. Never serialize returned values into public results. */
public interface TenantSecretAccessor {
    String SETTINGS_KEY = "__tenantSecretAccessor";
    String OPT_IN_HANDLER_PARAM = "tenantSecretRead";
    String SERVICE_TYPES_HANDLER_PARAM = "tenantSecretServiceTypes";
    String FIELDS_HANDLER_PARAM = "tenantSecretFields";

    /** Reads one enabled tenant CloudConfig field; platform defaults are never eligible. */
    String readCloudSecret(String serviceType, String providerCode, String field);
}
