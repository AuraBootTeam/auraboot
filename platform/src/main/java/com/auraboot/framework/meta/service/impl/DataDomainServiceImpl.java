package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.DataDomain;
import com.auraboot.framework.meta.entity.UserDataDomain;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DataDomainMapper;
import com.auraboot.framework.meta.mapper.UserDataDomainMapper;
import com.auraboot.framework.meta.service.DataDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of DataDomainService.
 *
 * <p>Provides domain CRUD, user-domain binding management, and SQL filter
 * generation for domain-based data isolation.
 *
 * @since 5.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataDomainServiceImpl implements DataDomainService {

    private final DataDomainMapper domainMapper;
    private final UserDataDomainMapper userDomainMapper;

    // ==================== Domain CRUD ====================

    @Override
    public List<DataDomain> listDomains() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return domainMapper.findByTenantId(tenantId);
    }

    @Override
    public DataDomain getDomain(Long id) {
        DataDomain domain = domainMapper.selectById(id);
        if (domain == null) {
            throw new MetaServiceException("Data domain not found: " + id);
        }
        return domain;
    }

    @Override
    public DataDomain getDomainByCode(String domainCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        DataDomain domain = domainMapper.findByCode(tenantId, domainCode);
        if (domain == null) {
            throw new MetaServiceException("Data domain not found: " + domainCode);
        }
        return domain;
    }

    @Override
    @Transactional
    public DataDomain createDomain(DataDomain domain) {
        Long tenantId = MetaContext.getCurrentTenantId();
        domain.setTenantId(tenantId);

        // Check for duplicate code
        DataDomain existing = domainMapper.findByCode(tenantId, domain.getDomainCode());
        if (existing != null) {
            throw new MetaServiceException("Domain code already exists: " + domain.getDomainCode());
        }

        // Validate parent domain if specified
        if (domain.getParentDomainId() != null) {
            DataDomain parent = domainMapper.selectById(domain.getParentDomainId());
            if (parent == null || !tenantId.equals(parent.getTenantId())) {
                throw new MetaServiceException("Parent domain not found: " + domain.getParentDomainId());
            }
        }

        domainMapper.insert(domain);
        evictCache();
        log.info("Created data domain: code={}, name={}", domain.getDomainCode(), domain.getDomainName());
        return domain;
    }

    @Override
    @Transactional
    public DataDomain updateDomain(Long id, DataDomain updates) {
        DataDomain domain = getDomain(id);
        Long tenantId = MetaContext.getCurrentTenantId();

        if (!tenantId.equals(domain.getTenantId())) {
            throw new MetaServiceException("Data domain not found: " + id);
        }

        if (updates.getDomainName() != null) {
            domain.setDomainName(updates.getDomainName());
        }
        if (updates.getDescription() != null) {
            domain.setDescription(updates.getDescription());
        }
        if (updates.getEnabled() != null) {
            domain.setEnabled(updates.getEnabled());
        }
        if (updates.getParentDomainId() != null) {
            // Validate no circular reference
            if (updates.getParentDomainId().equals(id)) {
                throw new MetaServiceException("Domain cannot be its own parent");
            }
            domain.setParentDomainId(updates.getParentDomainId());
        }

        domainMapper.updateById(domain);
        evictCache();
        log.info("Updated data domain: id={}, code={}", id, domain.getDomainCode());
        return domain;
    }

    @Override
    @Transactional
    public void deleteDomain(Long id) {
        DataDomain domain = getDomain(id);
        Long tenantId = MetaContext.getCurrentTenantId();

        if (!tenantId.equals(domain.getTenantId())) {
            throw new MetaServiceException("Data domain not found: " + id);
        }

        // Check for child domains
        List<DataDomain> children = domainMapper.findChildren(tenantId, id);
        if (!children.isEmpty()) {
            throw new MetaServiceException("Cannot delete domain with child domains. Delete children first.");
        }

        // Soft delete via MyBatis Plus @TableLogic
        domainMapper.deleteById(id);
        evictCache();
        log.info("Deleted data domain: id={}, code={}", id, domain.getDomainCode());
    }

    @Override
    public List<DataDomain> getChildren(Long parentDomainId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return domainMapper.findChildren(tenantId, parentDomainId);
    }

    // ==================== User-Domain Bindings ====================

    @Override
    @Transactional
    public UserDataDomain assignUser(Long domainId, Long userId, boolean isPrimary) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // Verify domain exists
        DataDomain domain = getDomain(domainId);
        if (!tenantId.equals(domain.getTenantId())) {
            throw new MetaServiceException("Data domain not found: " + domainId);
        }

        // Check if binding already exists
        UserDataDomain existing = userDomainMapper.findByUserAndDomain(userId, domainId);
        if (existing != null) {
            // Update primary flag if needed
            if (existing.getIsPrimary() != isPrimary) {
                existing.setIsPrimary(isPrimary);
                userDomainMapper.updateById(existing);
            }
            evictCache();
            return existing;
        }

        UserDataDomain binding = new UserDataDomain();
        binding.setTenantId(tenantId);
        binding.setUserId(userId);
        binding.setDomainId(domainId);
        binding.setIsPrimary(isPrimary);

        userDomainMapper.insert(binding);
        evictCache();
        log.info("Assigned user {} to domain {} (primary={})", userId, domainId, isPrimary);
        return binding;
    }

    @Override
    @Transactional
    public void removeUser(Long domainId, Long userId) {
        userDomainMapper.deleteByUserAndDomain(userId, domainId);
        evictCache();
        log.info("Removed user {} from domain {}", userId, domainId);
    }

    @Override
    public List<DataDomain> getUserDomains(Long userId) {
        List<Long> domainIds = userDomainMapper.findDomainIdsByUserId(userId);
        if (domainIds.isEmpty()) {
            return Collections.emptyList();
        }
        return domainMapper.selectBatchIds(domainIds);
    }

    @Override
    @Cacheable(value = "userDataDomainIds",
            key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #userId")
    public Set<Long> getUserDomainIdsWithDescendants(Long userId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> directDomainIds = userDomainMapper.findDomainIdsByUserId(userId);

        if (directDomainIds.isEmpty()) {
            return Collections.emptySet();
        }

        // Include all descendant domains for each assigned domain
        Set<Long> allDomainIds = new HashSet<>();
        for (Long domainId : directDomainIds) {
            List<Long> descendants = domainMapper.findDescendantIds(tenantId, domainId);
            allDomainIds.addAll(descendants);
        }

        return allDomainIds;
    }

    @Override
    public List<Long> getDomainUserIds(Long domainId) {
        return userDomainMapper.findUserIdsByDomainId(domainId);
    }

    // ==================== Domain Filtering ====================

    @Override
    public String buildDomainFilter(String modelCode, Long userId) {
        Set<Long> domainIds = getUserDomainIdsWithDescendants(userId);

        // If user has no domain assignments, no filter applied (open access)
        if (domainIds.isEmpty()) {
            return "";
        }

        // Build SQL IN clause for domain_id column
        String idList = domainIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));

        return "AND domain_id IN (" + idList + ")";
    }

    @Override
    public List<Map<String, Object>> filterByDomain(String modelCode, Long userId,
                                                     List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return records;
        }

        Set<Long> domainIds = getUserDomainIdsWithDescendants(userId);

        // No domain assignments = no filter (open access)
        if (domainIds.isEmpty()) {
            return records;
        }

        return records.stream()
                .filter(record -> {
                    Object domainId = record.get("domain_id");
                    if (domainId == null) {
                        // Records without domain_id are visible to everyone
                        return true;
                    }
                    long did = domainId instanceof Number
                            ? ((Number) domainId).longValue()
                            : Long.parseLong(domainId.toString());
                    return domainIds.contains(did);
                })
                .collect(Collectors.toList());
    }

    @Override
    @CacheEvict(value = "userDataDomainIds", allEntries = true)
    public void evictCache() {
        log.debug("Evicted data domain caches");
    }
}
