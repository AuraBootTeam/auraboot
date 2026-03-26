package com.auraboot.framework.saas.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SaasKernelErrorCode {

    // Bootstrap (5000-5099)
    SYSTEM_NOT_INITIALIZED(5000, "System is not initialized"),
    SYSTEM_ALREADY_INITIALIZED(5001, "System is already initialized"),
    BOOTSTRAP_IN_PROGRESS(5002, "Bootstrap is in progress"),
    BOOTSTRAP_FAILED(5003, "Bootstrap failed"),
    INVALID_SYSTEM_MODE(5004, "Invalid system mode"),

    // License (5100-5199)
    LICENSE_INVALID(5100, "License signature verification failed"),
    LICENSE_EXPIRED(5101, "License has expired"),
    LICENSE_REVOKED(5102, "License has been revoked"),
    TENANT_QUOTA_EXCEEDED(5103, "Tenant quota exceeded"),
    USER_QUOTA_EXCEEDED(5104, "User quota exceeded"),
    STORAGE_QUOTA_EXCEEDED(5105, "Storage quota exceeded"),
    FEATURE_NOT_ENABLED(5106, "Feature is not enabled for current edition"),

    // System Tenant (5200-5299)
    SYSTEM_TENANT_ACCESS_DENIED(5200, "Unauthorized access to system tenant data"),

    // Marketplace (5300-5399)
    MARKETPLACE_INSTALL_TOKEN_INVALID(5300, "Install token is invalid or expired"),
    MARKETPLACE_INSTALL_TOKEN_CONSUMED(5301, "Install token has already been used"),
    MARKETPLACE_CHECKSUM_MISMATCH(5302, "Plugin package checksum mismatch"),
    MARKETPLACE_SIGNATURE_INVALID(5303, "Plugin package signature verification failed"),
    MARKETPLACE_VERSION_INCOMPATIBLE(5304, "Platform version is not compatible"),

    // System Config (5400-5499)
    CONFIG_KEY_NOT_FOUND(5400, "System config key not found"),
    CONFIG_READONLY(5401, "System config key is readonly and cannot be modified");

    private final int code;
    private final String message;
}
