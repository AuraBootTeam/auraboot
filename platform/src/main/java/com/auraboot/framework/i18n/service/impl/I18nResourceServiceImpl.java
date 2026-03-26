package com.auraboot.framework.i18n.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.mapper.I18nResourceMapper;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Set;

/**
 * I18n Resource Service Implementation
 *
 * @author AuraBoot
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class I18nResourceServiceImpl implements I18nResourceService {

    private final I18nResourceMapper i18nResourceMapper;

    // ==================== Basic CRUD ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public I18nResource create(I18nResource resource) {
        validateResource(resource);

        resource.setPid(UniqueIdGenerator.generate());
        resource.setTenantId(getCurrentTenantId());
        resource.setCreatedAt(Instant.now());
        resource.setUpdatedAt(Instant.now());
        resource.setCreatedBy(MetaContext.getCurrentUserId());
        resource.setDeletedFlag(false);

        if (resource.getStatus() == null) {
            resource.setStatus(I18nResource.STATUS_APPROVED);
        }

        i18nResourceMapper.insert(resource);
        return resource;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public I18nResource update(String pid, I18nResource resource) {
        I18nResource existing = findByPid(pid);
        if (existing == null) {
            throw new BusinessException(ResponseCode.BadParam, "I18n resource not found: " + pid);
        }

        existing.setValue(resource.getValue());
        existing.setSource(resource.getSource());
        existing.setRefType(resource.getRefType());
        existing.setRefId(resource.getRefId());
        existing.setStatus(resource.getStatus());
        existing.setUpdatedAt(Instant.now());
        existing.setUpdatedBy(MetaContext.getCurrentUserId());

        i18nResourceMapper.updateById(existing);
        return existing;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String pid) {
        I18nResource existing = findByPid(pid);
        if (existing != null) {
            i18nResourceMapper.deleteById(existing.getId());
        }
    }

    @Override
    public I18nResource findByPid(String pid) {
        return i18nResourceMapper.selectByPid(pid);
    }

    @Override
    public I18nResource findByKeyAndLang(String key, String lang) {
        return i18nResourceMapper.selectByKeyAndLang(getCurrentTenantId(), key, lang);
    }

    // ==================== Batch Operations ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public I18nResource upsert(I18nResource resource) {
        validateResource(resource);

        resource.setPid(UniqueIdGenerator.generate());
        resource.setTenantId(getCurrentTenantId());
        resource.setCreatedBy(MetaContext.getCurrentUserId());

        if (resource.getStatus() == null) {
            resource.setStatus(I18nResource.STATUS_APPROVED);
        }

        i18nResourceMapper.upsert(resource);
        return resource;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchUpsert(List<I18nResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return 0;
        }

        Long tenantId = getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        int count = 0;
        for (I18nResource resource : resources) {
            resource.setPid(UniqueIdGenerator.generate());
            resource.setTenantId(tenantId);
            resource.setCreatedBy(userId);

            if (resource.getStatus() == null) {
                resource.setStatus(I18nResource.STATUS_APPROVED);
            }

            count += i18nResourceMapper.upsert(resource);
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchInsert(List<I18nResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return 0;
        }

        Long tenantId = getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        for (I18nResource resource : resources) {
            resource.setPid(UniqueIdGenerator.generate());
            resource.setTenantId(tenantId);
            resource.setCreatedBy(userId);

            if (resource.getStatus() == null) {
                resource.setStatus(I18nResource.STATUS_APPROVED);
            }
        }

        return i18nResourceMapper.batchInsertIgnore(resources);
    }

    // ==================== Query Operations ====================

    @Override
    public List<I18nResource> findAllByLang(String lang) {
        Long tenantId = getCurrentTenantId();
        List<I18nResource> tenantResources = i18nResourceMapper.selectAllByLang(tenantId, lang);

        // Also include system-level resources (tenant_id = 0)
        if (tenantId != 0L) {
            List<I18nResource> systemResources = i18nResourceMapper.selectAllByLang(0L, lang);
            // Merge: tenant resources override system resources
            Map<String, I18nResource> merged = new LinkedHashMap<>();
            for (I18nResource resource : systemResources) {
                merged.put(resource.getI18nKey(), resource);
            }
            for (I18nResource resource : tenantResources) {
                merged.put(resource.getI18nKey(), resource);
            }
            return new ArrayList<>(merged.values());
        }

        // When tenantId is 0 (unauthenticated request like /api/i18n/{locale}),
        // also load all tenant-level translations since i18n data is non-sensitive
        // and the endpoint is public (WhiteList). Without this, plugin-imported
        // translations (stored under real tenant IDs) would never appear.
        List<I18nResource> allTenantResources = i18nResourceMapper.selectAllByLangAllTenants(lang);
        Map<String, I18nResource> merged = new LinkedHashMap<>();
        for (I18nResource resource : tenantResources) {
            merged.put(resource.getI18nKey(), resource);
        }
        // Tenant-level translations override system-level for same key
        for (I18nResource resource : allTenantResources) {
            merged.put(resource.getI18nKey(), resource);
        }
        return new ArrayList<>(merged.values());
    }

    @Override
    public List<I18nResource> findByKeyPrefix(String lang, String prefix) {
        return i18nResourceMapper.selectByKeyPrefix(getCurrentTenantId(), lang, prefix);
    }

    @Override
    public List<I18nResource> findBySource(String source) {
        return i18nResourceMapper.selectBySource(getCurrentTenantId(), source);
    }

    @Override
    public List<I18nResource> findByRef(String refType, Long refId) {
        return i18nResourceMapper.selectByRef(getCurrentTenantId(), refType, refId);
    }

    @Override
    public IPage<I18nResource> findPage(int pageNum, int pageSize, String lang, String source, String status, String keyPrefix, String keyword) {
        Page<I18nResource> page = new Page<>(pageNum, pageSize);
        return i18nResourceMapper.selectPageList(page, getCurrentTenantId(), lang, source, status, keyPrefix, keyword);
    }

    // ==================== Compilation ====================

    @Override
    public Map<String, String> getResourceMapByLang(String lang) {
        List<I18nResource> resources = findAllByLang(lang);
        return resources.stream()
            .collect(Collectors.toMap(
                I18nResource::getI18nKey,
                I18nResource::getValue,
                (v1, v2) -> v2,  // In case of duplicate keys, use the latter
                LinkedHashMap::new
            ));
    }

    @Override
    public Map<String, Object> getNestedResourceMapByLang(String lang) {
        Map<String, String> flatMap = getResourceMapByLang(lang);
        Map<String, Object> nestedMap = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : flatMap.entrySet()) {
            setNestedValue(nestedMap, entry.getKey(), entry.getValue());
        }

        return nestedMap;
    }

    /**
     * Set a value in a nested map using dot-separated key
     */
    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> map, String key, String value) {
        String[] parts = key.split("\\.");
        Map<String, Object> current = map;

        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            current.computeIfAbsent(part, k -> new LinkedHashMap<String, Object>());
            Object next = current.get(part);
            if (next instanceof Map) {
                current = (Map<String, Object>) next;
            } else {
                // If there's already a value at this path, we need to handle the conflict
                // For now, we'll overwrite with a new map
                Map<String, Object> newMap = new LinkedHashMap<>();
                current.put(part, newMap);
                current = newMap;
            }
        }

        current.put(parts[parts.length - 1], value);
    }

    // ==================== Statistics ====================

    @Override
    public List<String> getDistinctLangs() {
        return i18nResourceMapper.selectDistinctLangs(getCurrentTenantId());
    }

    @Override
    public Map<String, Long> countByLang() {
        List<Map<String, Object>> results = i18nResourceMapper.countByLang(getCurrentTenantId());
        return results.stream()
            .collect(Collectors.toMap(
                m -> (String) m.get("lang"),
                m -> ((Number) m.get("count")).longValue()
            ));
    }

    @Override
    public Map<String, Long> countBySource() {
        List<Map<String, Object>> results = i18nResourceMapper.countBySource(getCurrentTenantId());
        return results.stream()
            .collect(Collectors.toMap(
                m -> (String) m.get("source"),
                m -> ((Number) m.get("count")).longValue()
            ));
    }

    // ==================== Model Integration ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncFromModel(Long modelId, String modelCode, String displayName) {
        if (!StringUtils.hasText(displayName)) {
            return;
        }

        String key = "model." + modelCode + "._meta.label";

        I18nResource resource = I18nResource.builder()
            .i18nKey(key)
            .lang(I18nResource.LANG_ZH_CN)
            .value(displayName)
            .source(I18nResource.SOURCE_MODEL)
            .refType(I18nResource.REF_TYPE_MODEL)
            .refId(modelId)
            .status(I18nResource.STATUS_APPROVED)
            .build();

        upsert(resource);
        log.debug("Synced i18n from model: {} -> {}", key, displayName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncFromField(Long fieldId, String modelCode, String fieldCode, String displayName, String placeholder, String description) {
        List<I18nResource> resources = new ArrayList<>();
        String keyPrefix = "model." + modelCode + "." + fieldCode;

        // Label
        if (StringUtils.hasText(displayName)) {
            resources.add(I18nResource.builder()
                .i18nKey(keyPrefix + ".label")
                .lang(I18nResource.LANG_ZH_CN)
                .value(displayName)
                .source(I18nResource.SOURCE_MODEL)
                .refType(I18nResource.REF_TYPE_FIELD)
                .refId(fieldId)
                .status(I18nResource.STATUS_APPROVED)
                .build());
        }

        // Placeholder
        if (StringUtils.hasText(placeholder)) {
            resources.add(I18nResource.builder()
                .i18nKey(keyPrefix + ".placeholder")
                .lang(I18nResource.LANG_ZH_CN)
                .value(placeholder)
                .source(I18nResource.SOURCE_MODEL)
                .refType(I18nResource.REF_TYPE_FIELD)
                .refId(fieldId)
                .status(I18nResource.STATUS_APPROVED)
                .build());
        }

        // Description
        if (StringUtils.hasText(description)) {
            resources.add(I18nResource.builder()
                .i18nKey(keyPrefix + ".description")
                .lang(I18nResource.LANG_ZH_CN)
                .value(description)
                .source(I18nResource.SOURCE_MODEL)
                .refType(I18nResource.REF_TYPE_FIELD)
                .refId(fieldId)
                .status(I18nResource.STATUS_APPROVED)
                .build());
        }

        if (!resources.isEmpty()) {
            batchUpsert(resources);
            log.debug("Synced {} i18n entries from field: {}.{}", resources.size(), modelCode, fieldCode);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByRef(String refType, Long refId) {
        int deleted = i18nResourceMapper.deleteByRef(getCurrentTenantId(), refType, refId);
        log.debug("Deleted {} i18n entries by ref: {}:{}", deleted, refType, refId);
    }

    // ==================== Workflow Operations ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public I18nResource submitReview(String pid) {
        I18nResource resource = findByPid(pid);
        if (resource == null) {
            throw new BusinessException(ResponseCode.BadParam, "I18n resource not found: " + pid);
        }
        if (!I18nResource.STATUS_DRAFT.equals(resource.getStatus())) {
            throw new BusinessException(ResponseCode.BadParam,
                "Cannot submit for review: current status is " + resource.getStatus() + ", expected DRAFT");
        }
        resource.setStatus(I18nResource.STATUS_REVIEW);
        resource.setRejectReason(null);
        resource.setUpdatedAt(Instant.now());
        resource.setUpdatedBy(MetaContext.getCurrentUserId());
        i18nResourceMapper.updateById(resource);
        log.info("I18n resource {} submitted for review by user {}", pid, MetaContext.getCurrentUserId());
        return resource;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public I18nResource approve(String pid) {
        I18nResource resource = findByPid(pid);
        if (resource == null) {
            throw new BusinessException(ResponseCode.BadParam, "I18n resource not found: " + pid);
        }
        if (!I18nResource.STATUS_REVIEW.equals(resource.getStatus())) {
            throw new BusinessException(ResponseCode.BadParam,
                "Cannot approve: current status is " + resource.getStatus() + ", expected REVIEW");
        }
        Long currentUserId = MetaContext.getCurrentUserId();
        resource.setStatus(I18nResource.STATUS_APPROVED);
        resource.setRejectReason(null);
        resource.setReviewedBy(currentUserId);
        resource.setReviewedAt(Instant.now());
        resource.setUpdatedAt(Instant.now());
        resource.setUpdatedBy(currentUserId);
        i18nResourceMapper.updateById(resource);
        log.info("I18n resource {} approved by user {}", pid, currentUserId);
        return resource;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public I18nResource reject(String pid, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ResponseCode.BadParam, "Rejection reason is required");
        }
        I18nResource resource = findByPid(pid);
        if (resource == null) {
            throw new BusinessException(ResponseCode.BadParam, "I18n resource not found: " + pid);
        }
        if (!I18nResource.STATUS_REVIEW.equals(resource.getStatus())) {
            throw new BusinessException(ResponseCode.BadParam,
                "Cannot reject: current status is " + resource.getStatus() + ", expected REVIEW");
        }
        Long currentUserId = MetaContext.getCurrentUserId();
        resource.setStatus(I18nResource.STATUS_DRAFT);
        resource.setRejectReason(reason);
        resource.setReviewedBy(currentUserId);
        resource.setReviewedAt(Instant.now());
        resource.setUpdatedAt(Instant.now());
        resource.setUpdatedBy(currentUserId);
        i18nResourceMapper.updateById(resource);
        log.info("I18n resource {} rejected by user {} with reason: {}", pid, currentUserId, reason);
        return resource;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public I18nResource updateStatus(String pid, String newStatus) {
        if (!StringUtils.hasText(newStatus)) {
            throw new BusinessException(ResponseCode.BadParam, "Status is required");
        }
        if (!Set.of(I18nResource.STATUS_DRAFT, I18nResource.STATUS_REVIEW,
                    I18nResource.STATUS_APPROVED, I18nResource.STATUS_DEPRECATED).contains(newStatus)) {
            throw new BusinessException(ResponseCode.BadParam, "Invalid status: " + newStatus);
        }
        I18nResource resource = findByPid(pid);
        if (resource == null) {
            throw new BusinessException(ResponseCode.BadParam, "I18n resource not found: " + pid);
        }
        resource.setStatus(newStatus);
        resource.setUpdatedAt(Instant.now());
        resource.setUpdatedBy(MetaContext.getCurrentUserId());
        i18nResourceMapper.updateById(resource);
        return resource;
    }

    // ==================== Helper Methods ====================

    private Long getCurrentTenantId() {
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            return tenantId != null ? tenantId : 0L;
        } catch (IllegalStateException e) {
            // MetaContext not initialized (e.g., unauthenticated request)
            // Return 0L for system-level resources
            log.debug("MetaContext not initialized, using system tenant (0)");
            return 0L;
        }
    }

    private void validateResource(I18nResource resource) {
        if (!StringUtils.hasText(resource.getI18nKey())) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "i18n_key is required");
        }
        if (!StringUtils.hasText(resource.getLang())) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "lang is required");
        }
        if (!StringUtils.hasText(resource.getValue())) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "value is required");
        }
        if (!StringUtils.hasText(resource.getSource())) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "source is required");
        }
    }
}
