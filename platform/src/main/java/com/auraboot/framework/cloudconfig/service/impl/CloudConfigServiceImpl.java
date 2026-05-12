package com.auraboot.framework.cloudconfig.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.cloudconfig.dto.CloudConfigResponse;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.auraboot.framework.cloudconfig.mapper.CloudConfigMapper;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of CloudConfigService.
 * <p>
 * Handles PLATFORM/TENANT config layering, automatic encryption/decryption
 * of sensitive fields, and masking for display.
 *
 * @since 6.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudConfigServiceImpl implements CloudConfigService {

    /** Sensitive field names that must be encrypted at rest and masked on read. */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "apiKey",
            "secretId", "secretKey", "appSecret", "clientSecret",
            "privateKey", "password", "accessKey", "accessToken", "refreshToken"
    );

    private final CloudConfigMapper cloudConfigMapper;
    private final FieldEncryptionService fieldEncryptionService;
    private final ObjectMapper objectMapper;

    @Override
    public CloudConfig getEffectiveConfig(Long tenantId, String serviceType, String providerCode) {
        CloudConfig config = cloudConfigMapper.getEffectiveConfig(tenantId, normalize(serviceType), providerCode);
        if (config != null) {
            config.setConfig(decryptConfigJson(config.getConfig()));
        }
        return config;
    }

    @Override
    public List<CloudConfig> getEnabledProviders(Long tenantId, String serviceType) {
        List<CloudConfig> configs = cloudConfigMapper.getEnabledProviders(tenantId, normalize(serviceType));
        configs.forEach(c -> c.setConfig(decryptConfigJson(c.getConfig())));
        return configs;
    }

    @Override
    @Transactional
    public void saveConfig(CloudConfigSaveRequest request) {
        String configLevel = normalize(request.getConfigLevel());
        String serviceType = normalize(request.getServiceType());
        String encryptedConfig = encryptConfigJson(request.getConfig());

        if (request.getPid() != null && !request.getPid().isBlank()) {
            // Update existing
            CloudConfig existing = cloudConfigMapper.findByPid(request.getPid());
            if (existing == null) {
                throw new BusinessException("Cloud config not found: " + request.getPid());
            }

            existing.setConfigLevel(configLevel);
            existing.setServiceType(serviceType);
            existing.setProviderCode(request.getProviderCode());
            existing.setConfig(encryptedConfig);
            existing.setEnabled(request.getEnabled());
            existing.setPriority(request.getPriority() != null ? request.getPriority() : 0);
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(MetaContext.getCurrentUserPid());

            cloudConfigMapper.updateById(existing);
            log.info("Updated cloud config: pid={}, serviceType={}, providerCode={}",
                    existing.getPid(), serviceType, request.getProviderCode());
        } else {
            // Create new
            CloudConfig entity = new CloudConfig();
            entity.setPid(UlidGenerator.generate());
            entity.setConfigLevel(configLevel);
            entity.setServiceType(serviceType);
            entity.setProviderCode(request.getProviderCode());
            entity.setConfig(encryptedConfig);
            entity.setEnabled(request.getEnabled());
            entity.setPriority(request.getPriority() != null ? request.getPriority() : 0);
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            entity.setCreatedBy(MetaContext.getCurrentUserPid());
            entity.setUpdatedBy(MetaContext.getCurrentUserPid());

            // PLATFORM level: tenantId = null; TENANT level: current tenant
            if ("platform".equals(configLevel)) {
                entity.setTenantId(null);
            } else {
                entity.setTenantId(MetaContext.getCurrentTenantId());
            }

            cloudConfigMapper.insert(entity);
            log.info("Created cloud config: pid={}, level={}, serviceType={}, providerCode={}",
                    entity.getPid(), entity.getConfigLevel(), serviceType, request.getProviderCode());
        }
    }

    @Override
    public CloudConfigResponse getConfigMasked(String pid) {
        CloudConfig config = cloudConfigMapper.findByPid(pid);
        if (config == null) {
            return null;
        }
        return toMaskedResponse(config);
    }

    @Override
    public List<CloudConfigResponse> listConfigs(String configLevel) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<CloudConfig> configs = cloudConfigMapper.listByLevel(configLevel, tenantId);
        return configs.stream().map(this::toMaskedResponse).toList();
    }

    @Override
    @Transactional
    public void deleteConfig(String pid) {
        CloudConfig config = cloudConfigMapper.findByPid(pid);
        if (config == null) {
            throw new BusinessException("Cloud config not found: " + pid);
        }
        // Soft delete via MyBatis Plus @TableLogic
        cloudConfigMapper.deleteById(config.getId());
        log.info("Soft-deleted cloud config: pid={}, serviceType={}, providerCode={}",
                pid, config.getServiceType(), config.getProviderCode());
    }

    @Override
    public List<CloudConfig> getAllByServiceType(String serviceType) {
        List<CloudConfig> configs = cloudConfigMapper.getAllByServiceType(normalize(serviceType));
        configs.forEach(c -> c.setConfig(decryptConfigJson(c.getConfig())));
        return configs;
    }

    @Override
    public CloudConfig getByPidDecrypted(String pid) {
        CloudConfig config = cloudConfigMapper.findByPid(pid);
        if (config != null) {
            config.setConfig(decryptConfigJson(config.getConfig()));
        }
        return config;
    }

    // ==================== Private helpers ====================

    /**
     * Encrypt sensitive fields in the config JSON string.
     * Iterates all top-level fields; if the field name is in SENSITIVE_FIELDS,
     * encrypts the value using FieldEncryptionService.
     */
    private String encryptConfigJson(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return configJson;
        }

        try {
            JsonNode root = objectMapper.readTree(configJson);
            if (!root.isObject()) {
                return configJson;
            }

            ObjectNode obj = (ObjectNode) root;
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (SENSITIVE_FIELDS.contains(entry.getKey()) && entry.getValue().isTextual()) {
                    String plainValue = entry.getValue().asText();
                    obj.put(entry.getKey(), fieldEncryptionService.encrypt(plainValue));
                }
            }

            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to encrypt config JSON fields, storing as-is: {}", e.getMessage());
            return configJson;
        }
    }

    /**
     * Decrypt all ENC:-prefixed values in the config JSON string.
     */
    private String decryptConfigJson(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return configJson;
        }

        try {
            JsonNode root = objectMapper.readTree(configJson);
            if (!root.isObject()) {
                return configJson;
            }

            ObjectNode obj = (ObjectNode) root;
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getValue().isTextual()) {
                    String value = entry.getValue().asText();
                    if (fieldEncryptionService.isEncrypted(value)) {
                        obj.put(entry.getKey(), fieldEncryptionService.decrypt(value));
                    }
                }
            }

            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to decrypt config JSON fields: {}", e.getMessage());
            return configJson;
        }
    }

    /**
     * Convert entity to response DTO with sensitive fields masked.
     */
    private CloudConfigResponse toMaskedResponse(CloudConfig config) {
        CloudConfigResponse response = new CloudConfigResponse();
        response.setPid(config.getPid());
        response.setConfigLevel(config.getConfigLevel());
        response.setTenantId(config.getTenantId());
        response.setServiceType(config.getServiceType());
        response.setProviderCode(config.getProviderCode());
        response.setConfig(fieldEncryptionService.maskJsonFields(config.getConfig(), SENSITIVE_FIELDS));
        response.setEnabled(config.getEnabled());
        response.setPriority(config.getPriority());
        response.setCreatedAt(config.getCreatedAt());
        response.setUpdatedAt(config.getUpdatedAt());
        return response;
    }

    private String normalize(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
