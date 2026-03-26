package com.auraboot.framework.plugin.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.plugin.dto.PluginImportRequest;
import com.auraboot.framework.plugin.dto.PluginImportResult;
import com.auraboot.framework.plugin.service.PluginTransactionalImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for transactional plugin import operations.
 *
 * Endpoints:
 * - POST /api/plugins/import-tx?dryRun=true  — conflict detection only
 * - POST /api/plugins/import-tx              — full transactional import
 * - GET  /api/plugins/import-tx/history      — import history list
 */
@RestController
@RequestMapping("/api/plugins/import-tx")
public class PluginTransactionalImportController {

    @Autowired
    private PluginTransactionalImportService pluginTransactionalImportService;

    /**
     * Import a plugin or perform a dry-run conflict check.
     *
     * @param request  plugin import payload (schemas, permissions, menus)
     * @param dryRun   if true, only checks for conflicts without importing
     * @return import result with status, conflicts, or resource counts
     */
    @PostMapping
    public ApiResponse<PluginImportResult> importPlugin(
            @RequestBody PluginImportRequest request,
            @RequestParam(defaultValue = "false") boolean dryRun) {

        PluginImportResult result = pluginTransactionalImportService.importPlugin(request, dryRun);

        if ("conflict".equals(result.getStatus())) {
            return ApiResponse.error(40001, result.getErrorMessage(), result);
        }

        return ApiResponse.success(result);
    }

    /**
     * Query plugin import history.
     *
     * @param pluginCode optional filter by plugin code
     * @param pageNum    page number (1-based, default 1)
     * @param pageSize   page size (default 20)
     * @return list of import history records
     */
    @GetMapping("/history")
    public ApiResponse<List<PluginImportResult>> getImportHistory(
            @RequestParam(required = false) String pluginCode,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        List<PluginImportResult> history = pluginTransactionalImportService.getImportHistory(pluginCode, pageNum, pageSize);
        return ApiResponse.success(history);
    }
}
