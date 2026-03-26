package com.auraboot.framework.environment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.environment.dto.*;
import com.auraboot.framework.environment.service.EnvironmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Implementation of EnvironmentService.
 */
@Slf4j
@Service
public class EnvironmentServiceImpl implements EnvironmentService {

    @Autowired
    private EnvironmentMapper environmentMapper;

    @Override
    public List<EnvironmentResponse> listAll(Long tenantId) {
        List<Environment> envs = environmentMapper.findAllByTenant(tenantId);
        return envs.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Override
    public EnvironmentResponse getByPid(String pid, Long tenantId) {
        Environment env = findByPidOrThrow(pid, tenantId);
        return toResponse(env);
    }

    @Override
    @Transactional
    public EnvironmentResponse create(EnvironmentRequest request, Long tenantId, Long userId) {
        // Check for duplicate code within tenant
        Environment existing = environmentMapper.findByTenantAndCode(tenantId, request.getCode());
        if (existing != null) {
            throw new IllegalArgumentException("Environment code '" + request.getCode() + "' already exists");
        }

        Environment env = new Environment();
        env.setPid(UniqueIdGenerator.generate());
        env.setTenantId(tenantId);
        env.setCode(request.getCode());
        env.setName(request.getName());
        env.setDescription(request.getDescription());
        env.setApiBaseUrl(request.getApiBaseUrl());
        env.setDbConnectionInfo(request.getDbConnectionInfo());
        env.setStatus(StatusConstants.ACTIVE);
        env.setIsDefault(request.getIsDefault() != null ? request.getIsDefault() : false);
        env.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        env.setCreatedAt(new Date());
        env.setUpdatedAt(new Date());
        env.setCreatedBy(userId);
        env.setUpdatedBy(userId);
        env.setDeletedFlag(false);

        // If this is default, clear other defaults
        if (Boolean.TRUE.equals(env.getIsDefault())) {
            environmentMapper.clearDefaultForTenant(tenantId);
        }

        environmentMapper.insert(env);
        log.info("Created environment: code={}, tenant={}", env.getCode(), tenantId);

        return toResponse(env);
    }

    @Override
    @Transactional
    public EnvironmentResponse update(String pid, EnvironmentRequest request, Long tenantId, Long userId) {
        Environment env = findByPidOrThrow(pid, tenantId);

        // If code changed, check uniqueness
        if (!env.getCode().equals(request.getCode())) {
            Environment dup = environmentMapper.findByTenantAndCode(tenantId, request.getCode());
            if (dup != null) {
                throw new IllegalArgumentException("Environment code '" + request.getCode() + "' already exists");
            }
            env.setCode(request.getCode());
        }

        env.setName(request.getName());
        env.setDescription(request.getDescription());
        env.setApiBaseUrl(request.getApiBaseUrl());
        env.setDbConnectionInfo(request.getDbConnectionInfo());
        env.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : env.getSortOrder());
        env.setUpdatedAt(new Date());
        env.setUpdatedBy(userId);

        if (request.getIsDefault() != null && request.getIsDefault() && !Boolean.TRUE.equals(env.getIsDefault())) {
            environmentMapper.clearDefaultForTenant(tenantId);
            env.setIsDefault(true);
        } else if (request.getIsDefault() != null) {
            env.setIsDefault(request.getIsDefault());
        }

        environmentMapper.updateById(env);
        log.info("Updated environment: pid={}, code={}", pid, env.getCode());

        return toResponse(env);
    }

    @Override
    @Transactional
    public void delete(String pid, Long tenantId) {
        Environment env = findByPidOrThrow(pid, tenantId);
        env.setDeletedFlag(true);
        env.setUpdatedAt(new Date());
        environmentMapper.updateById(env);
        log.info("Deleted environment: pid={}, code={}", pid, env.getCode());
    }

    @Override
    public EnvironmentExportData exportConfig(String code, Long tenantId) {
        Environment env = environmentMapper.findByTenantAndCode(tenantId, code);
        if (env == null) {
            throw new IllegalArgumentException("Environment not found: " + code);
        }

        EnvironmentExportData data = new EnvironmentExportData();
        data.setCode(env.getCode());
        data.setName(env.getName());
        data.setDescription(env.getDescription());
        data.setApiBaseUrl(env.getApiBaseUrl());
        data.setDbConnectionInfo(env.getDbConnectionInfo());
        data.setIsDefault(env.getIsDefault());
        data.setSortOrder(env.getSortOrder());
        data.setExportedAt(new Date());

        return data;
    }

    @Override
    @Transactional
    public EnvironmentResponse importConfig(String code, EnvironmentExportData data, Long tenantId, Long userId) {
        Environment existing = environmentMapper.findByTenantAndCode(tenantId, code);

        if (existing != null) {
            // Update existing environment with imported data
            existing.setName(data.getName() != null ? data.getName() : existing.getName());
            existing.setDescription(data.getDescription());
            existing.setApiBaseUrl(data.getApiBaseUrl());
            existing.setDbConnectionInfo(data.getDbConnectionInfo());
            if (data.getSortOrder() != null) {
                existing.setSortOrder(data.getSortOrder());
            }
            existing.setUpdatedAt(new Date());
            existing.setUpdatedBy(userId);

            if (Boolean.TRUE.equals(data.getIsDefault()) && !Boolean.TRUE.equals(existing.getIsDefault())) {
                environmentMapper.clearDefaultForTenant(tenantId);
                existing.setIsDefault(true);
            }

            environmentMapper.updateById(existing);
            log.info("Imported config into existing environment: code={}", code);
            return toResponse(existing);
        } else {
            // Create new environment from imported data
            EnvironmentRequest request = new EnvironmentRequest();
            request.setCode(code);
            request.setName(data.getName() != null ? data.getName() : code);
            request.setDescription(data.getDescription());
            request.setApiBaseUrl(data.getApiBaseUrl());
            request.setDbConnectionInfo(data.getDbConnectionInfo());
            request.setIsDefault(data.getIsDefault());
            request.setSortOrder(data.getSortOrder());
            return create(request, tenantId, userId);
        }
    }

    @Override
    public EnvironmentDiffResponse diff(String sourceCode, String targetCode, Long tenantId) {
        Environment source = environmentMapper.findByTenantAndCode(tenantId, sourceCode);
        Environment target = environmentMapper.findByTenantAndCode(tenantId, targetCode);

        if (source == null) {
            throw new IllegalArgumentException("Source environment not found: " + sourceCode);
        }
        if (target == null) {
            throw new IllegalArgumentException("Target environment not found: " + targetCode);
        }

        EnvironmentDiffResponse response = new EnvironmentDiffResponse();
        response.setSourceCode(sourceCode);
        response.setTargetCode(targetCode);

        List<EnvironmentDiffResponse.DiffEntry> diffs = new ArrayList<>();

        // Compare top-level fields
        addDiffIfChanged(diffs, "name", source.getName(), target.getName());
        addDiffIfChanged(diffs, "description", source.getDescription(), target.getDescription());
        addDiffIfChanged(diffs, "apiBaseUrl", source.getApiBaseUrl(), target.getApiBaseUrl());
        addDiffIfChanged(diffs, "status", source.getStatus(), target.getStatus());
        addDiffIfChanged(diffs, "isDefault", source.getIsDefault(), target.getIsDefault());

        // Compare dbConnectionInfo (flat key comparison)
        Map<String, Object> srcDb = source.getDbConnectionInfo() != null ? source.getDbConnectionInfo() : Collections.emptyMap();
        Map<String, Object> tgtDb = target.getDbConnectionInfo() != null ? target.getDbConnectionInfo() : Collections.emptyMap();

        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(srcDb.keySet());
        allKeys.addAll(tgtDb.keySet());

        for (String key : allKeys) {
            Object srcVal = srcDb.get(key);
            Object tgtVal = tgtDb.get(key);
            addDiffIfChanged(diffs, "dbConnectionInfo." + key, srcVal, tgtVal);
        }

        response.setDifferences(diffs);
        return response;
    }

    // ---- Private helpers ----

    private Environment findByPidOrThrow(String pid, Long tenantId) {
        QueryWrapper<Environment> qw = new QueryWrapper<>();
        qw.eq("pid", pid)
          .eq("tenant_id", tenantId)
          .eq("deleted_flag", false);
        Environment env = environmentMapper.selectOne(qw);
        if (env == null) {
            throw new IllegalArgumentException("Environment not found: " + pid);
        }
        return env;
    }

    private EnvironmentResponse toResponse(Environment env) {
        EnvironmentResponse resp = new EnvironmentResponse();
        resp.setPid(env.getPid());
        resp.setCode(env.getCode());
        resp.setName(env.getName());
        resp.setDescription(env.getDescription());
        resp.setApiBaseUrl(env.getApiBaseUrl());
        resp.setDbConnectionInfo(env.getDbConnectionInfo());
        resp.setStatus(env.getStatus());
        resp.setIsDefault(env.getIsDefault());
        resp.setSortOrder(env.getSortOrder());
        resp.setCreatedAt(env.getCreatedAt());
        resp.setUpdatedAt(env.getUpdatedAt());
        return resp;
    }

    private void addDiffIfChanged(List<EnvironmentDiffResponse.DiffEntry> diffs, String key, Object srcVal, Object tgtVal) {
        boolean srcNull = srcVal == null;
        boolean tgtNull = tgtVal == null;

        if (srcNull && tgtNull) {
            return;
        }

        EnvironmentDiffResponse.DiffEntry entry = new EnvironmentDiffResponse.DiffEntry();
        entry.setKey(key);
        entry.setSourceValue(srcVal);
        entry.setTargetValue(tgtVal);

        if (srcNull) {
            entry.setChangeType("added");
            diffs.add(entry);
        } else if (tgtNull) {
            entry.setChangeType("removed");
            diffs.add(entry);
        } else if (!srcVal.equals(tgtVal)) {
            entry.setChangeType("changed");
            diffs.add(entry);
        }
    }
}
