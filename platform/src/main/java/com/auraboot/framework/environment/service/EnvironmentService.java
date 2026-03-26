package com.auraboot.framework.environment.service;

import com.auraboot.framework.environment.dto.*;

import java.util.List;

/**
 * Service for managing deployment environments (dev, staging, prod, etc.).
 */
public interface EnvironmentService {

    /**
     * List all environments for the current tenant.
     */
    List<EnvironmentResponse> listAll(Long tenantId);

    /**
     * Get a single environment by PID.
     */
    EnvironmentResponse getByPid(String pid, Long tenantId);

    /**
     * Create a new environment.
     */
    EnvironmentResponse create(EnvironmentRequest request, Long tenantId, Long userId);

    /**
     * Update an existing environment by PID.
     */
    EnvironmentResponse update(String pid, EnvironmentRequest request, Long tenantId, Long userId);

    /**
     * Delete an environment by PID (soft delete).
     */
    void delete(String pid, Long tenantId);

    /**
     * Export the configuration of an environment.
     */
    EnvironmentExportData exportConfig(String code, Long tenantId);

    /**
     * Import configuration into an environment.
     * If the environment code doesn't exist, it creates one; otherwise it updates.
     */
    EnvironmentResponse importConfig(String code, EnvironmentExportData data, Long tenantId, Long userId);

    /**
     * Compute the diff between two environments' configurations.
     */
    EnvironmentDiffResponse diff(String sourceCode, String targetCode, Long tenantId);
}
