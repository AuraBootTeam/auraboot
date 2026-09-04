package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.dto.IdentityProviderSaveRequest;
import com.auraboot.framework.auth.dto.IdentityProviderSummary;
import com.auraboot.framework.auth.entity.IdentityProviderInstance;
import com.auraboot.framework.auth.mapper.IdentityProviderInstanceMapper;
import com.auraboot.framework.auth.service.IdentityProviderManagementService;
import com.auraboot.framework.auth.service.FederatedIdentityFeatureGate;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class IdentityProviderManagementServiceImpl implements IdentityProviderManagementService {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "wechat_web", "oidc", "google", "google_workspace", "microsoft365",
            "apple", "wecom", "dingtalk", "feishu", "ldap");
    private static final Set<String> SECRET_KEYS = Set.of(
            "clientsecret", "appsecret", "privatekey", "bindpassword", "secret", "password");

    private final IdentityProviderInstanceMapper mapper;
    private final ObjectMapper objectMapper;
    private final FederatedIdentityFeatureGate featureGate;

    @Override
    public List<IdentityProviderSummary> list(String applicationCode, Long tenantId) {
        return mapper.listManaged(normalize(applicationCode, "business-web"), requireTenant(tenantId));
    }

    @Override
    @Transactional
    public IdentityProviderSummary save(IdentityProviderSaveRequest request, Long tenantId) {
        Long ownerTenantId = requireTenant(tenantId);
        validate(request);
        if ("active".equals(request.getStatus())) {
            featureGate.requireEnabled(request.getProviderType());
        }
        String applicationCode = normalize(request.getApplicationCode(), "business-web");
        Long applicationId = mapper.findApplicationId(applicationCode);
        if (applicationId == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Login application not found");
        }
        Long channelId = mapper.findChannelId(
                applicationId,
                normalize(request.getChannelCode(), "default-business-web"),
                ownerTenantId);
        if (channelId == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Active login channel not found");
        }

        IdentityProviderInstance instance = hasText(request.getPid())
                ? mapper.findEditable(request.getPid(), applicationCode, ownerTenantId)
                : null;
        if (hasText(request.getPid()) && instance == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Global or another tenant's identity provider cannot be edited");
        }
        if (instance == null) {
            instance = new IdentityProviderInstance();
            instance.setPid(UlidGenerator.generate());
            instance.setApplicationId(applicationId);
            instance.setTenantId(ownerTenantId);
        }
        instance.setCode(request.getCode().trim());
        instance.setDisplayName(request.getDisplayName().trim());
        instance.setProviderType(request.getProviderType().trim());
        instance.setStatus(request.getStatus().trim().toLowerCase());
        instance.setConfig(canonicalJson(request.getConfig()));
        instance.setSecretRef(normalizeBlank(request.getSecretRef()));
        if (instance.getId() == null) {
            mapper.insert(instance);
        } else {
            mapper.updateById(instance);
        }

        Long bindingId = mapper.findBindingId(channelId, instance.getId());
        if (bindingId == null) {
            mapper.insertBinding(
                    UlidGenerator.generate(),
                    applicationId,
                    channelId,
                    instance.getId(),
                    instance.getProviderType(),
                    instance.getStatus(),
                    request.getSortOrder());
        } else {
            mapper.updateBinding(
                    bindingId,
                    instance.getProviderType(),
                    instance.getStatus(),
                    request.getSortOrder());
        }
        String savedPid = instance.getPid();
        return mapper.listManaged(applicationCode, ownerTenantId).stream()
                .filter(summary -> savedPid.equals(summary.getPid()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Saved identity provider is not readable"));
    }

    @Override
    @Transactional
    public void setStatus(String pid, String status, Long tenantId) {
        String normalizedStatus = normalizeStatus(status);
        IdentityProviderInstance instance = mapper.findEditableByPid(
                pid, requireTenant(tenantId));
        if (instance == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Editable identity provider not found");
        }
        if ("active".equals(normalizedStatus)) {
            featureGate.requireEnabled(instance.getProviderType());
        }
        instance.setStatus(normalizedStatus);
        mapper.updateById(instance);
        mapper.updateBindingStatus(instance.getId(), normalizedStatus);
    }

    private void validate(IdentityProviderSaveRequest request) {
        if (request == null || !hasText(request.getCode()) || !hasText(request.getDisplayName())) {
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "Identity provider code and display name are required");
        }
        if (!request.getCode().matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,99}")) {
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "Identity provider code contains unsupported characters");
        }
        if (!hasText(request.getProviderType()) || !SUPPORTED_TYPES.contains(request.getProviderType())) {
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "Unsupported identity provider type");
        }
        request.setStatus(normalizeStatus(request.getStatus()));
        request.setSortOrder(request.getSortOrder() == null ? 100 : request.getSortOrder());
        if (request.getSortOrder() < 0) {
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "Sort order must not be negative");
        }
        String secretRef = normalizeBlank(request.getSecretRef());
        if (secretRef != null && !secretRef.matches("cloud-config:[a-zA-Z0-9_-]+")) {
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "Secret reference must use cloud-config:{providerCode}");
        }
        validateConfig(request.getConfig(), "ldap".equals(request.getProviderType()));
    }

    private void validateConfig(String configJson, boolean ldap) {
        try {
            JsonNode config = objectMapper.readTree(normalize(configJson, "{}"));
            if (config == null || !config.isObject()) {
                throw new BusinessException(ResponseCode.CommonValidationFailed,
                        "Identity provider config must be a JSON object");
            }
            validateNoSecrets(config);
            if (!ldap) {
                int redirects = 0;
                if (hasText(config.path("redirectUri").asText(null))) {
                    validateRedirect(config.path("redirectUri").asText());
                    redirects++;
                }
                JsonNode redirectUris = config.path("redirectUris");
                if (redirectUris.isArray()) {
                    for (JsonNode redirect : redirectUris) {
                        if (!redirect.isTextual()) {
                            throw new BusinessException(ResponseCode.CommonValidationFailed,
                                    "redirectUris entries must be strings");
                        }
                        validateRedirect(redirect.asText());
                        redirects++;
                    }
                }
                if (redirects == 0) {
                    throw new BusinessException(ResponseCode.CommonValidationFailed,
                            "At least one exact redirect URI is required");
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "Identity provider config JSON is invalid", ex);
        }
    }

    private void validateRedirect(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            boolean webScheme = "https".equalsIgnoreCase(scheme)
                    || "http".equalsIgnoreCase(scheme);
            if (scheme == null
                    || !("https".equalsIgnoreCase(scheme)
                    || "http".equalsIgnoreCase(scheme)
                    || "auraboot".equalsIgnoreCase(scheme))
                    || (webScheme && !hasText(uri.getHost()))
                    || ("http".equalsIgnoreCase(scheme) && !isLoopbackHost(uri.getHost()))
                    || uri.getFragment() != null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "Redirect URI is invalid: " + value);
        }
    }

    private void validateNoSecrets(JsonNode node) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (SECRET_KEYS.contains(entry.getKey().toLowerCase())) {
                    throw new BusinessException(ResponseCode.CommonValidationFailed,
                            "Secrets must be stored in CloudConfig and referenced by secretRef");
                }
                validateNoSecrets(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::validateNoSecrets);
        }
    }

    private String canonicalJson(String json) {
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(normalize(json, "{}")));
        } catch (Exception ex) {
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "Identity provider config JSON is invalid", ex);
        }
    }

    private static String normalizeStatus(String value) {
        String normalized = normalize(value, "active").toLowerCase();
        if (!"active".equals(normalized) && !"disabled".equals(normalized)) {
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "Identity provider status must be active or disabled");
        }
        return normalized;
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private static Long requireTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "Business tenant context is required");
        }
        return tenantId;
    }

    private static String normalize(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static String normalizeBlank(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
