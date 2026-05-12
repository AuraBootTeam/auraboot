package com.auraboot.framework.cloudconfig.service;

import com.auraboot.framework.cloudconfig.dto.CloudConfigResponse;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.entity.CloudConfig;

import java.util.List;

/**
 * Service for unified cloud vendor configuration management.
 * <p>
 * Supports PLATFORM/TENANT layering: tenant-level configs override platform defaults.
 * Handles automatic encryption/decryption of sensitive fields in config JSON.
 *
 * @since 6.3.0
 */
public interface CloudConfigService {

    /**
     * Get the effective config for a service type and provider code.
     * Tenant-level config takes priority; falls back to platform-level.
     * Config JSON is auto-decrypted before returning.
     *
     * @param tenantId     the tenant ID
     * @param serviceType  sms, email, oauth, storage, cdn, llm
     * @param providerCode provider identifier (e.g., tencent_sms, google)
     * @return the effective config with decrypted JSON, or null if none found
     */
    CloudConfig getEffectiveConfig(Long tenantId, String serviceType, String providerCode);

    /**
     * Get all enabled providers for a service type (both tenant and platform levels).
     * Config JSON is auto-decrypted.
     *
     * @param tenantId    the tenant ID
     * @param serviceType sms, email, oauth, storage, cdn, llm
     * @return list of enabled configs, ordered by level (TENANT first) and priority
     */
    List<CloudConfig> getEnabledProviders(Long tenantId, String serviceType);

    /**
     * Create or update a cloud configuration.
     * Sensitive fields in the config JSON are auto-encrypted before saving.
     *
     * @param request the save request
     */
    void saveConfig(CloudConfigSaveRequest request);

    /**
     * Get a single config by PID with sensitive fields masked for display.
     *
     * @param pid the config PID
     * @return response DTO with masked config, or null if not found
     */
    CloudConfigResponse getConfigMasked(String pid);

    /**
     * List all configs at a given level with sensitive fields masked.
     *
     * @param configLevel PLATFORM or TENANT
     * @return list of response DTOs
     */
    List<CloudConfigResponse> listConfigs(String configLevel);

    /**
     * Soft-delete a config by PID.
     *
     * @param pid the config PID
     */
    void deleteConfig(String pid);

    /**
     * Get all enabled configs for a service type across all tenants and platform level.
     * Used for provider discovery (e.g., listing all known LLM providers).
     *
     * @param serviceType sms, email, oauth, storage, cdn, llm
     * @return list of enabled configs ordered by priority
     */
    List<CloudConfig> getAllByServiceType(String serviceType);

    /**
     * Get a single config by PID with decrypted config JSON.
     * Used for connection testing where actual credentials are needed.
     *
     * @param pid the config PID
     * @return the config entity with decrypted JSON, or null if not found
     */
    CloudConfig getByPidDecrypted(String pid);
}
