package com.auraboot.framework.saas.constant;

public final class SystemConfigKeys {

    private SystemConfigKeys() {}

    // System scope (bootstrap-time immutable)
    public static final String SYSTEM_MODE = "system.mode";
    public static final String SYSTEM_INITIALIZED = "system.initialized";
    public static final String SYSTEM_SETUP_AT = "system.setup_at";
    public static final String SYSTEM_DEFAULT_TENANT_ID = "system.default_tenant_id";
    public static final String SYSTEM_DB_UUID = "system.db_uuid";
    public static final String SYSTEM_INSTANCE_URL = "system.instance_url";

    // System scope (mutable)
    public static final String SYSTEM_PLATFORM_NAME = "system.platform_name";
    public static final String SYSTEM_ALLOW_SELF_REGISTRATION = "system.allow_self_registration";

    // Marketplace scope
    public static final String MARKETPLACE_URL = "marketplace.url";
    public static final String MARKETPLACE_MODE = "marketplace.mode";
    public static final String MARKETPLACE_MIRROR_PATH = "marketplace.mirror_path";
    public static final String MARKETPLACE_ALLOW_UPLOAD = "marketplace.allow_upload";
}
