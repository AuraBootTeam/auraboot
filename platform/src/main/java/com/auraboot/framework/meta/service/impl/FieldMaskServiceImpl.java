package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.FieldMaskConfig;
import com.auraboot.framework.meta.mapper.FieldMaskConfigMapper;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of FieldMaskService.
 * Provides configurable field-level data masking with per-tenant caching.
 *
 * @since 5.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldMaskServiceImpl implements FieldMaskService {

    private final FieldMaskConfigMapper maskConfigMapper;
    private final RoleMapper roleMapper;

    // ==================== Configuration CRUD ====================

    @Override
    public List<FieldMaskConfig> listConfigs(String modelCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return maskConfigMapper.findAllByModelCode(tenantId, modelCode);
    }

    @Override
    @Cacheable(value = "fieldMaskConfig", key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #modelCode")
    public List<FieldMaskConfig> getEnabledConfigs(String modelCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return maskConfigMapper.findByModelCode(tenantId, modelCode);
    }

    @Override
    public FieldMaskConfig saveConfig(FieldMaskConfig config) {
        Long tenantId = MetaContext.getCurrentTenantId();
        config.setTenantId(tenantId);

        // Check if a config already exists for this model + field
        FieldMaskConfig existing = maskConfigMapper.findByModelAndField(
                tenantId, config.getModelCode(), config.getFieldCode());

        if (existing != null) {
            // Update existing config
            config.setId(existing.getId());
            maskConfigMapper.updateById(config);
            log.info("Updated field mask config: model={}, field={}, type={}",
                    config.getModelCode(), config.getFieldCode(), config.getMaskType());
        } else {
            // Insert new config
            maskConfigMapper.insert(config);
            log.info("Created field mask config: model={}, field={}, type={}",
                    config.getModelCode(), config.getFieldCode(), config.getMaskType());
        }

        evictCache(config.getModelCode());
        return config;
    }

    @Override
    public void deleteConfig(Long id) {
        Long tenantId = MetaContext.getCurrentTenantId();
        FieldMaskConfig config = maskConfigMapper.selectById(id);
        if (config != null && tenantId.equals(config.getTenantId())) {
            maskConfigMapper.deleteByTenantAndId(tenantId, id);
            evictCache(config.getModelCode());
            log.info("Deleted field mask config: id={}, model={}, field={}",
                    id, config.getModelCode(), config.getFieldCode());
        }
    }

    // ==================== Runtime Masking ====================

    @Override
    public List<Map<String, Object>> applyMaskingForList(String modelCode,
                                                          List<Map<String, Object>> records,
                                                          Long userId) {
        return applyMasking(modelCode, records, userId, "list");
    }

    @Override
    public Map<String, Object> applyMaskingForDetail(String modelCode,
                                                      Map<String, Object> record,
                                                      Long userId) {
        if (record == null) {
            return null;
        }
        List<Map<String, Object>> result = applyMasking(modelCode, List.of(record), userId, "detail");
        return result.isEmpty() ? record : result.get(0);
    }

    @Override
    public List<Map<String, Object>> applyMaskingForExport(String modelCode,
                                                            List<Map<String, Object>> records,
                                                            Long userId) {
        return applyMasking(modelCode, records, userId, "export");
    }

    @Override
    public String maskValue(String value, String maskType, String maskPattern, String replacementChar) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String rc = (replacementChar != null && !replacementChar.isEmpty()) ? replacementChar : "*";

        switch (maskType.toLowerCase(Locale.ROOT)) {
            case "phone":
                return maskPhone(value, rc);
            case "email":
                return maskEmail(value, rc);
            case "id_card":
                return maskIdCard(value, rc);
            case "bank_card":
                return maskBankCard(value, rc);
            case "name":
                return maskName(value, rc);
            case "partial":
                return maskPartial(value, maskPattern, rc);
            case "full":
                return rc.repeat(Math.min(value.length(), 10));
            case "custom":
                return maskCustom(value, maskPattern, rc);
            default:
                log.warn("Unknown mask type: {}", maskType);
                return value;
        }
    }

    @Override
    @CacheEvict(value = "fieldMaskConfig", key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #modelCode")
    public void evictCache(String modelCode) {
        log.debug("Evicted field mask config cache for model: {}", modelCode);
    }

    // ==================== Private Helpers ====================

    /**
     * Core masking logic: load configs, check role exemptions, apply masks.
     */
    private List<Map<String, Object>> applyMasking(String modelCode,
                                                    List<Map<String, Object>> records,
                                                    Long userId,
                                                    String context) {
        if (records == null || records.isEmpty()) {
            return records;
        }

        List<FieldMaskConfig> configs = getEnabledConfigs(modelCode);
        if (configs.isEmpty()) {
            return records;
        }

        // Filter configs applicable to the context (LIST, DETAIL, EXPORT)
        List<FieldMaskConfig> applicable = configs.stream()
                .filter(c -> isApplicableToContext(c, context))
                .collect(Collectors.toList());

        if (applicable.isEmpty()) {
            return records;
        }

        // Check user role exemptions
        Set<String> userRoleCodes = getUserRoleCodes(userId);

        // Build field → config map, excluding exempt fields
        Map<String, FieldMaskConfig> fieldMasks = new HashMap<>();
        for (FieldMaskConfig config : applicable) {
            if (!isExempt(config, userRoleCodes)) {
                fieldMasks.put(config.getFieldCode(), config);
            }
        }

        if (fieldMasks.isEmpty()) {
            return records;
        }

        // Apply masking to each record
        List<Map<String, Object>> masked = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            Map<String, Object> maskedRecord = new LinkedHashMap<>(record);
            for (Map.Entry<String, FieldMaskConfig> entry : fieldMasks.entrySet()) {
                String fieldCode = entry.getKey();
                if (maskedRecord.containsKey(fieldCode) && maskedRecord.get(fieldCode) != null) {
                    Object original = maskedRecord.get(fieldCode);
                    FieldMaskConfig cfg = entry.getValue();
                    String maskedValue = maskValue(
                            original.toString(),
                            cfg.getMaskType(),
                            cfg.getMaskPattern(),
                            cfg.getReplacementChar());
                    maskedRecord.put(fieldCode, maskedValue);
                }
            }
            masked.add(maskedRecord);
        }

        return masked;
    }

    /**
     * Check if a config is applicable to the given context (LIST, DETAIL, EXPORT).
     */
    private boolean isApplicableToContext(FieldMaskConfig config, String context) {
        switch (context) {
            case "list":
                return Boolean.TRUE.equals(config.getApplyToList());
            case "detail":
                return Boolean.TRUE.equals(config.getApplyToDetail());
            case "export":
                return Boolean.TRUE.equals(config.getApplyToExport());
            default:
                return true;
        }
    }

    /**
     * Check if the user is exempt from masking for a given config.
     */
    private boolean isExempt(FieldMaskConfig config, Set<String> userRoleCodes) {
        String exemptRoles = config.getExemptRoles();
        if (exemptRoles == null || exemptRoles.isBlank()) {
            return false;
        }

        Set<String> exempt = Arrays.stream(exemptRoles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        return exempt.stream().anyMatch(userRoleCodes::contains);
    }

    /**
     * Get the set of role codes for a user in the current tenant.
     */
    private Set<String> getUserRoleCodes(Long userId) {
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            Long memberId = MetaContext.getCurrentMemberId();
            if (memberId == null) {
                return Collections.emptySet();
            }
            List<Role> roles = roleMapper.findByMemberIdAndTenantId(memberId, tenantId);
            return roles.stream()
                    .map(Role::getCode)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Failed to load user roles for masking exemption check: userId={}", userId, e);
            return Collections.emptySet();
        }
    }

    // ==================== Masking Algorithms ====================

    /**
     * Phone: 138****5678 — show first 3 and last 4 digits.
     */
    private String maskPhone(String phone, String rc) {
        if (phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + rc.repeat(4) + phone.substring(phone.length() - 4);
    }

    /**
     * Email: gh***@163.com — show first 2 chars and domain.
     */
    private String maskEmail(String email, String rc) {
        int atIndex = email.indexOf('@');
        if (atIndex < 0) {
            return email;
        }
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        int showChars = Math.min(2, local.length());
        return local.substring(0, showChars) + rc.repeat(3) + domain;
    }

    /**
     * ID Card: 3201**********1234 — show first 4 and last 4 digits.
     */
    private String maskIdCard(String idCard, String rc) {
        if (idCard.length() < 8) {
            return idCard;
        }
        return idCard.substring(0, 4) + rc.repeat(idCard.length() - 8) + idCard.substring(idCard.length() - 4);
    }

    /**
     * Bank Card: ****5678 — show only last 4 digits.
     */
    private String maskBankCard(String bankCard, String rc) {
        if (bankCard.length() < 4) {
            return bankCard;
        }
        return rc.repeat(bankCard.length() - 4) + bankCard.substring(bankCard.length() - 4);
    }

    /**
     * Name: first character + asterisks.
     */
    private String maskName(String name, String rc) {
        if (name.length() < 2) {
            return name;
        }
        return name.charAt(0) + rc.repeat(name.length() - 1);
    }

    /**
     * Partial: show first N and last M characters.
     * Pattern format: "N,M" (e.g. "3,4").
     */
    private String maskPartial(String value, String pattern, String rc) {
        int firstN = 3;
        int lastM = 4;

        if (pattern != null && pattern.contains(",")) {
            try {
                String[] parts = pattern.split(",");
                firstN = Integer.parseInt(parts[0].trim());
                lastM = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                log.warn("Invalid PARTIAL mask pattern: '{}', using defaults", pattern);
            }
        }

        if (value.length() <= firstN + lastM) {
            // Too short to mask meaningfully
            return value.charAt(0) + rc.repeat(value.length() - 1);
        }

        int maskLen = value.length() - firstN - lastM;
        return value.substring(0, firstN) + rc.repeat(maskLen) + value.substring(value.length() - lastM);
    }

    /**
     * Custom: literal replacement. Regex execution is intentionally avoided
     * because mask patterns are tenant-configured data.
     */
    private String maskCustom(String value, String pattern, String rc) {
        if (pattern == null || pattern.isEmpty()) {
            return value;
        }
        String replacement = rc.repeat(pattern.length());
        StringBuilder masked = new StringBuilder(value.length());
        int fromIndex = 0;
        int matchIndex = value.indexOf(pattern, fromIndex);
        while (matchIndex >= 0) {
            masked.append(value, fromIndex, matchIndex);
            masked.append(replacement);
            fromIndex = matchIndex + pattern.length();
            matchIndex = value.indexOf(pattern, fromIndex);
        }
        masked.append(value, fromIndex, value.length());
        return masked.toString();
    }
}
