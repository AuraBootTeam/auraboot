package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.service.BpmExportImportService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for BPM process definition export/import.
 */
@RestController
@RequestMapping("/api/bpm")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.BPM_DEFINITION_MANAGE)
public class BpmExportImportController {

    private final BpmExportImportService exportImportService;

    /**
     * Export a process definition package.
     */
    @GetMapping("/export/{processKey}")
    public ResponseEntity<Map<String, Object>> exportPackage(@PathVariable String processKey) {
        return ResponseEntity.ok(exportImportService.exportPackage(processKey));
    }

    /**
     * Validate a process package before importing.
     */
    @PostMapping("/import/validate")
    public ResponseEntity<Map<String, Object>> validateImport(@RequestBody Map<String, Object> pkg) {
        return ResponseEntity.ok(exportImportService.validatePackage(pkg));
    }

    /**
     * Execute the import of a process package.
     */
    @PostMapping("/import/execute")
    public ResponseEntity<Map<String, Object>> executeImport(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> pkg = (Map<String, Object>) request.get("package");
        String strategy = (String) request.getOrDefault("strategy", "skip_existing");
        return ResponseEntity.ok(exportImportService.executeImport(pkg, strategy));
    }
}
