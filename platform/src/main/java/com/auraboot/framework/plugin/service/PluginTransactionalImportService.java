package com.auraboot.framework.plugin.service;

import com.auraboot.framework.plugin.dto.PluginImportRequest;
import com.auraboot.framework.plugin.dto.PluginImportResult;

import java.util.List;

/**
 * Plugin transactional import service interface.
 * Provides transactional import with conflict detection and rollback support.
 */
public interface PluginTransactionalImportService {

    /**
     * Import plugin resources with full transaction closure.
     * If dryRun is true, only performs conflict detection without importing.
     *
     * @param request the import request payload
     * @param dryRun  if true, only check conflicts; if false, actually import
     * @return import result with status, conflicts, or resource counts
     */
    PluginImportResult importPlugin(PluginImportRequest request, boolean dryRun);

    /**
     * Query import history for a specific plugin (or all plugins).
     *
     * @param pluginCode optional filter by plugin code (null for all)
     * @param pageNum    page number (1-based)
     * @param pageSize   page size
     * @return list of import results
     */
    List<PluginImportResult> getImportHistory(String pluginCode, int pageNum, int pageSize);
}
