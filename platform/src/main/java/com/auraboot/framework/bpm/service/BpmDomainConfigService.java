package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmDomainConfig;
import com.auraboot.framework.bpm.mapper.BpmDomainConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Service for managing BPM domain configurations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BpmDomainConfigService {

    private final BpmDomainConfigMapper domainConfigMapper;

    /**
     * List all domain configs for the current tenant.
     */
    public List<BpmDomainConfig> list() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return domainConfigMapper.selectList(
                new QueryWrapper<BpmDomainConfig>()
                        .eq("tenant_id", tenantId)
                        .eq("deleted_flag", false)
                        .orderByAsc("domain_name"));
    }

    /**
     * Get a domain config by PID.
     */
    public BpmDomainConfig getByPid(String pid) {
        BpmDomainConfig config = domainConfigMapper.findByPid(pid);
        if (config != null && !config.getTenantId().equals(MetaContext.getCurrentTenantId())) {
            return null; // Tenant isolation
        }
        return config;
    }

    /**
     * Get a domain config by domain code.
     */
    public BpmDomainConfig getByDomainCode(String domainCode) {
        return domainConfigMapper.findByDomainCode(domainCode);
    }

    /**
     * Create a new domain config.
     */
    @Transactional
    public BpmDomainConfig create(CreateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();

        if (domainConfigMapper.existsByDomainCode(request.domainCode())) {
            throw new IllegalArgumentException("Domain code already exists: " + request.domainCode());
        }

        String pid = UlidGenerator.generate();

        BpmDomainConfig config = BpmDomainConfig.builder()
                .pid(pid)
                .tenantId(tenantId)
                .domainCode(request.domainCode())
                .domainName(request.domainName())
                .modelCode(request.modelCode())
                .processKeys(request.processKeys())
                .listFields(request.listFields())
                .filterFields(request.filterFields())
                .sortFields(request.sortFields())
                .enabled(request.enabled() != null ? request.enabled() : true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .createdBy(MetaContext.getCurrentUserId())
                .build();

        domainConfigMapper.insert(config);
        log.info("Created domain config: domainCode={}, pid={}", request.domainCode(), pid);

        return config;
    }

    /**
     * Update an existing domain config.
     */
    @Transactional
    public BpmDomainConfig update(String pid, UpdateRequest request) {
        BpmDomainConfig existing = getByPid(pid);
        if (existing == null) {
            throw new IllegalArgumentException("Domain config not found: " + pid);
        }

        if (request.domainName() != null) {
            existing.setDomainName(request.domainName());
        }
        if (request.modelCode() != null) {
            existing.setModelCode(request.modelCode());
        }
        if (request.processKeys() != null) {
            existing.setProcessKeys(request.processKeys());
        }
        if (request.listFields() != null) {
            existing.setListFields(request.listFields());
        }
        if (request.filterFields() != null) {
            existing.setFilterFields(request.filterFields());
        }
        if (request.sortFields() != null) {
            existing.setSortFields(request.sortFields());
        }
        if (request.enabled() != null) {
            existing.setEnabled(request.enabled());
        }

        existing.setUpdatedAt(Instant.now());
        existing.setUpdatedBy(MetaContext.getCurrentUserId());
        domainConfigMapper.updateById(existing);

        log.info("Updated domain config: pid={}", pid);
        return existing;
    }

    /**
     * Delete a domain config (soft delete).
     */
    @Transactional
    public void delete(String pid) {
        BpmDomainConfig existing = getByPid(pid);
        if (existing == null) {
            throw new IllegalArgumentException("Domain config not found: " + pid);
        }

        domainConfigMapper.deleteById(existing.getId());

        log.info("Deleted domain config: pid={}", pid);
    }

    // ==================== Request Records ====================

    public record CreateRequest(
            String domainCode,
            String domainName,
            String modelCode,
            List<String> processKeys,
            List<Map<String, Object>> listFields,
            List<Map<String, Object>> filterFields,
            List<Map<String, Object>> sortFields,
            Boolean enabled
    ) {}

    public record UpdateRequest(
            String domainName,
            String modelCode,
            List<String> processKeys,
            List<Map<String, Object>> listFields,
            List<Map<String, Object>> filterFields,
            List<Map<String, Object>> sortFields,
            Boolean enabled
    ) {}
}
