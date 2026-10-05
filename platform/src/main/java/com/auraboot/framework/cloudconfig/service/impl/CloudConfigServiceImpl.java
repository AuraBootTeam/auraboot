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
import com.fasterxml.jackson.core.JsonProcessingException;
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
        validateConfigLevel(configLevel);
        Long tenantId = MetaContext.getCurrentTenantId();
        if ("tenant".equals(configLevel) && tenantId == null) {
            throw new BusinessException("Tenant context is required for tenant cloud config");
        }
        String serviceType = normalize(request.getServiceType());

        if (request.getPid() != null && !request.getPid().isBlank()) {
            // Update existing
            CloudConfig existing = cloudConfigMapper.findByPid(request.getPid(), tenantId);
            if (existing == null) {
                throw new BusinessException("Cloud config not found: " + request.getPid());
            }

            if (!configLevel.equals(existing.getConfigLevel())) {
                throw new BusinessException("Cloud config level cannot be changed");
            }
            existing.setServiceType(serviceType);
            existing.setProviderCode(request.getProviderCode());
            existing.setConfig(encryptConfigJson(request.getConfig(), existing.getConfig()));
            existing.setEnabled(request.getEnabled());
            existing.setPriority(request.getPriority() != null ? request.getPriority() : 0);
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(MetaContext.getCurrentUserPid());

            if (cloudConfigMapper.updateScoped(existing, tenantId) != 1) {
                throw new BusinessException("Cloud config update did not affect exactly one visible config");
            }
            log.info("Updated cloud config: pid={}, serviceType={}, providerCode={}",
                    existing.getPid(), serviceType, request.getProviderCode());
        } else {
            // Create new
            CloudConfig entity = new CloudConfig();
            entity.setPid(UlidGenerator.generate());
            entity.setConfigLevel(configLevel);
            entity.setServiceType(serviceType);
            entity.setProviderCode(request.getProviderCode());
            entity.setConfig(encryptConfigJson(request.getConfig(), null));
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
                entity.setTenantId(tenantId);
            }

            if (cloudConfigMapper.insert(entity) != 1) {
                throw new BusinessException("Cloud config create did not affect exactly one config");
            }
            log.info("Created cloud config: pid={}, level={}, serviceType={}, providerCode={}",
                    entity.getPid(), entity.getConfigLevel(), serviceType, request.getProviderCode());
        }
    }

    @Override
    public CloudConfigResponse getConfigMasked(String pid) {
        CloudConfig config = cloudConfigMapper.findByPid(pid, MetaContext.getCurrentTenantId());
        if (config == null) {
            return null;
        }
        return toMaskedResponse(config);
    }

    @Override
    public List<CloudConfigResponse> listConfigs(String configLevel) {
        Long tenantId = MetaContext.getCurrentTenantId();
        // Normalize on the way IN, exactly as saveConfig does. Writes store the level
        // lower-cased, and listByLevel compares it against the lowercase literals
        // 'tenant'/'platform' in BOTH its WHERE clause and its tenant-scoping <if>
        // branches. Passing the documented `PLATFORM` through raw therefore matched
        // nothing AND skipped scoping — an empty list that reads as "nothing is
        // configured", which is how duplicate provider rows get created.
        String normalizedLevel = normalize(configLevel);
        validateConfigLevel(normalizedLevel);
        List<CloudConfig> configs = cloudConfigMapper.listByLevel(normalizedLevel, tenantId);
        return configs.stream().map(this::toMaskedResponse).toList();
    }

    @Override
    @Transactional
    public void deleteConfig(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        CloudConfig config = cloudConfigMapper.findByPid(pid, tenantId);
        if (config == null) {
            throw new BusinessException("Cloud config not found: " + pid);
        }
        if (cloudConfigMapper.softDeleteScoped(pid, tenantId, Instant.now(), MetaContext.getCurrentUserPid()) != 1) {
            throw new BusinessException("Cloud config delete did not affect exactly one visible config");
        }
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
        CloudConfig config = cloudConfigMapper.findByPid(pid, MetaContext.getCurrentTenantId());
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
    private String encryptConfigJson(String configJson, String existingConfigJson) {
        if (configJson == null || configJson.isBlank()) {
            return configJson;
        }

        try {
            JsonNode root = objectMapper.readTree(configJson);
            if (!root.isObject()) {
                throw new BusinessException("Cloud config JSON must be an object");
            }
            JsonNode existing = existingConfigJson == null ? null : objectMapper.readTree(existingConfigJson);

            ObjectNode obj = (ObjectNode) root;
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (SENSITIVE_FIELDS.contains(entry.getKey()) && entry.getValue().isTextual()) {
                    String plainValue = entry.getValue().asText();
                    JsonNode previous = existing == null ? null : existing.get(entry.getKey());
                    if (previous != null && previous.isTextual()
                            && plainValue.equals(fieldEncryptionService.mask(previous.asText()))) {
                        // Masked admin responses round-trip without replacing the stored secret.
                        obj.set(entry.getKey(), previous);
                    } else {
                        obj.put(entry.getKey(), fieldEncryptionService.encrypt(plainValue));
                    }
                }
            }

            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new BusinessException("Invalid cloud config JSON", e);
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
        } catch (JsonProcessingException e) {
            throw new BusinessException("Invalid stored cloud config JSON", e);
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

    private void validateConfigLevel(String level) {
        if (!"platform".equals(level) && !"tenant".equals(level)) {
            throw new BusinessException("Cloud config level must be platform or tenant");
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
