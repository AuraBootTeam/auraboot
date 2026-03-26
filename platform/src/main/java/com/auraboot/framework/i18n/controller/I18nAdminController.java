package com.auraboot.framework.i18n.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.i18n.compiler.I18nCompiler;
import com.auraboot.framework.i18n.dto.I18nCoverageResponse;
import com.auraboot.framework.i18n.dto.I18nResourceCreateRequest;
import com.auraboot.framework.i18n.dto.I18nResourceUpdateRequest;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.dto.AiTranslateRequest;
import com.auraboot.framework.i18n.dto.AiTranslationResult;
import com.auraboot.framework.i18n.service.AiTranslationService;
import com.auraboot.framework.i18n.service.I18nCoverageService;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.i18n.service.OrphanKeyDetector;
import com.auraboot.framework.i18n.sync.I18nSyncService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * I18n Admin Controller - Management APIs for i18n resources
 *
 * @author AuraBoot
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/i18n")
@RequiredArgsConstructor
public class I18nAdminController {

    private final I18nResourceService i18nResourceService;
    private final I18nCompiler i18nCompiler;
    private final I18nService i18nService;
    private final I18nSyncService i18nSyncService;
    private final I18nCoverageService i18nCoverageService;
    private final OrphanKeyDetector orphanKeyDetector;
    private final AiTranslationService aiTranslationService;

    // ==================== CRUD Operations ====================

    /**
     * Create a new i18n resource
     */
    @PostMapping("/resources")
    public ApiResponse<I18nResource> create(@RequestBody I18nResourceCreateRequest request) {
        I18nResource resource = I18nResource.builder()
            .i18nKey(request.getKey())
            .lang(request.getLang())
            .value(request.getValue())
            .source(request.getSource() != null ? request.getSource() : I18nResource.SOURCE_IMPORT)
            .status(I18nResource.STATUS_APPROVED)
            .build();

        I18nResource created = i18nResourceService.create(resource);
        return ApiResponse.success(created);
    }

    /**
     * Update an existing i18n resource
     */
    @PutMapping("/resources/{pid}")
    public ApiResponse<I18nResource> update(
        @PathVariable String pid,
        @RequestBody I18nResourceUpdateRequest request
    ) {
        I18nResource resource = I18nResource.builder()
            .value(request.getValue())
            .source(request.getSource())
            .status(request.getStatus())
            .build();

        I18nResource updated = i18nResourceService.update(pid, resource);
        return ApiResponse.success(updated);
    }

    /**
     * Delete an i18n resource
     */
    @DeleteMapping("/resources/{pid}")
    public ApiResponse<Void> delete(@PathVariable String pid) {
        i18nResourceService.delete(pid);
        return ApiResponse.success(null);
    }

    /**
     * Get a single i18n resource by PID
     */
    @GetMapping("/resources/{pid}")
    public ApiResponse<I18nResource> getByPid(@PathVariable String pid) {
        I18nResource resource = i18nResourceService.findByPid(pid);
        return ApiResponse.success(resource);
    }

    /**
     * Get a single i18n resource by key and language
     */
    @GetMapping("/resources/by-key")
    public ApiResponse<I18nResource> getByKeyAndLang(
        @RequestParam String key,
        @RequestParam(defaultValue = "zh-CN") String lang
    ) {
        I18nResource resource = i18nResourceService.findByKeyAndLang(key, lang);
        return ApiResponse.success(resource);
    }

    // ==================== Query Operations ====================

    /**
     * Paginated query of i18n resources
     */
    @GetMapping("/resources")
    public ApiResponse<IPage<I18nResource>> listResources(
        @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String lang,
        @RequestParam(required = false) String source,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String keyPrefix,
        @RequestParam(required = false) String keyword
    ) {
        IPage<I18nResource> page = i18nResourceService.findPage(pageNum, pageSize, lang, source, status, keyPrefix, keyword);
        return ApiResponse.success(page);
    }

    /**
     * Get resources by key prefix (scope query)
     */
    @GetMapping("/resources/by-prefix")
    public ApiResponse<List<I18nResource>> getByKeyPrefix(
        @RequestParam String prefix,
        @RequestParam(defaultValue = "zh-CN") String lang
    ) {
        List<I18nResource> resources = i18nResourceService.findByKeyPrefix(lang, prefix);
        return ApiResponse.success(resources);
    }

    // ==================== AI Translation ====================

    /**
     * Generate DRAFT translations for missing keys using the configured LLM provider.
     *
     * <p>Keys that already have any translation entry (regardless of status) are skipped.
     * Each generated entry is written with {@code status=DRAFT} and {@code source=ai}.
     * When no LLM provider is configured the service falls back to using the source
     * locale value as a placeholder so reviewers know what still needs translating.
     *
     * @param request target locale, source locale, and max key count
     * @return summary of generated / skipped / error counts
     */
    @PostMapping("/ai-translate")
    public ApiResponse<AiTranslationResult> aiTranslate(@RequestBody AiTranslateRequest request) {
        log.info("AI translation requested: targetLocale={} sourceLocale={} maxKeys={}",
                request.getTargetLocale(), request.getSourceLocale(), request.getMaxKeys());
        AiTranslationResult result = aiTranslationService.translate(request);
        return ApiResponse.success(result);
    }

    // ==================== Coverage ====================

    /**
     * Get translation coverage statistics per locale.
     *
     * <p>The base locale is {@code zh-CN}.  Every other language is compared against it.
     * The response includes per-locale coverage percentages and a sample of up to 50 missing keys.
     */
    @GetMapping("/coverage")
    public ApiResponse<I18nCoverageResponse> getCoverage() {
        return ApiResponse.success(i18nCoverageService.computeCoverage());
    }

    // ==================== Statistics ====================

    /**
     * Get statistics overview
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        Map<String, Long> byLang = i18nResourceService.countByLang();
        Map<String, Long> bySource = i18nResourceService.countBySource();
        List<String> langs = i18nResourceService.getDistinctLangs();

        Map<String, Object> stats = Map.of(
            "byLang", byLang,
            "bySource", bySource,
            "languages", langs,
            "totalLanguages", langs.size()
        );

        return ApiResponse.success(stats);
    }

    // ==================== Compilation ====================

    /**
     * Trigger compilation for all languages
     */
    @PostMapping("/compile")
    public ApiResponse<I18nCompiler.CompileResult> compileAll() {
        log.info("Manual i18n compilation triggered");
        I18nCompiler.CompileResult result = i18nCompiler.compileAll();
        return ApiResponse.success(result);
    }

    /**
     * Trigger compilation for a specific language
     */
    @PostMapping("/compile/{lang}")
    public ApiResponse<I18nCompiler.CompileResult.LangResult> compileLang(@PathVariable String lang) {
        log.info("Manual i18n compilation triggered for language: {}", lang);
        I18nCompiler.CompileResult.LangResult result = i18nCompiler.compileLang(lang);

        // Clear cache for this language
        i18nService.clearCache(lang);

        return ApiResponse.success(result);
    }

    /**
     * Get compiled JSON for a language (without writing to file)
     */
    @GetMapping("/compiled/{lang}")
    public ApiResponse<String> getCompiledJson(@PathVariable String lang) {
        try {
            String json = i18nCompiler.getCompiledJson(lang);
            return ApiResponse.success(json);
        } catch (Exception e) {
            log.error("Failed to get compiled JSON for language: {}", lang, e);
            return ApiResponse.error("Failed to compile: " + e.getMessage());
        }
    }

    // ==================== Cache Management ====================

    /**
     * Clear i18n cache
     */
    @PostMapping("/cache/clear")
    public ApiResponse<Void> clearCache(@RequestParam(required = false) String lang) {
        i18nService.clearCache(lang);
        log.info("I18n cache cleared for: {}", lang != null ? lang : "all languages");
        return ApiResponse.success(null);
    }

    // ==================== Batch Operations ====================

    /**
     * Upsert a single resource (create or update)
     */
    @PostMapping("/resources/upsert")
    public ApiResponse<I18nResource> upsert(@RequestBody I18nResourceCreateRequest request) {
        I18nResource resource = I18nResource.builder()
            .i18nKey(request.getKey())
            .lang(request.getLang())
            .value(request.getValue())
            .source(request.getSource() != null ? request.getSource() : I18nResource.SOURCE_IMPORT)
            .status(I18nResource.STATUS_APPROVED)
            .build();

        I18nResource result = i18nResourceService.upsert(resource);
        return ApiResponse.success(result);
    }

    /**
     * Batch upsert resources
     */
    @PostMapping("/resources/batch-upsert")
    public ApiResponse<Map<String, Object>> batchUpsert(@RequestBody List<I18nResourceCreateRequest> requests) {
        List<I18nResource> resources = requests.stream()
            .map(req -> I18nResource.builder()
                .i18nKey(req.getKey())
                .lang(req.getLang())
                .value(req.getValue())
                .source(req.getSource() != null ? req.getSource() : I18nResource.SOURCE_IMPORT)
                .status(I18nResource.STATUS_APPROVED)
                .build())
            .toList();

        int count = i18nResourceService.batchUpsert(resources);

        return ApiResponse.success(Map.of(
            "total", requests.size(),
            "upserted", count
        ));
    }

    // ==================== Workflow Operations ====================

    /**
     * Submit a DRAFT translation for review (DRAFT → REVIEW)
     */
    @PostMapping("/resources/{pid}/submit-review")
    public ApiResponse<I18nResource> submitReview(@PathVariable String pid) {
        I18nResource resource = i18nResourceService.submitReview(pid);
        return ApiResponse.success(resource);
    }

    /**
     * Approve a translation under review (REVIEW → APPROVED)
     */
    @PostMapping("/resources/{pid}/approve")
    public ApiResponse<I18nResource> approve(@PathVariable String pid) {
        I18nResource resource = i18nResourceService.approve(pid);
        return ApiResponse.success(resource);
    }

    /**
     * Reject a translation under review, reverting to DRAFT (REVIEW → DRAFT)
     * Body: { "reason": "Rejection reason text" }
     */
    @PostMapping("/resources/{pid}/reject")
    public ApiResponse<I18nResource> reject(
        @PathVariable String pid,
        @RequestBody Map<String, String> body
    ) {
        String reason = body.get("reason");
        I18nResource resource = i18nResourceService.reject(pid, reason);
        return ApiResponse.success(resource);
    }

    /**
     * Update status directly (admin-level operation)
     * Body: { "status": "DRAFT|REVIEW|APPROVED|DEPRECATED" }
     */
    @PutMapping("/resources/{pid}/status")
    public ApiResponse<I18nResource> updateStatus(
        @PathVariable String pid,
        @RequestBody Map<String, String> body
    ) {
        String status = body.get("status");
        I18nResource resource = i18nResourceService.updateStatus(pid, status);
        return ApiResponse.success(resource);
    }

    // ==================== Sync Operations ====================

    /**
     * Sync i18n from all models and fields
     */
    @PostMapping("/sync")
    public ApiResponse<I18nSyncService.SyncResult> syncAll() {
        log.info("Manual i18n sync triggered");
        I18nSyncService.SyncResult result = i18nSyncService.syncAll();
        return ApiResponse.success(result);
    }

    /**
     * Sync and compile - sync all models/fields then compile to JSON
     */
    @PostMapping("/sync-and-compile")
    public ApiResponse<Map<String, Object>> syncAndCompile() {
        log.info("Manual i18n sync and compile triggered");

        // Sync
        I18nSyncService.SyncResult syncResult = i18nSyncService.syncAll();

        // Compile
        I18nCompiler.CompileResult compileResult = null;
        if (syncResult.isSuccess()) {
            compileResult = i18nCompiler.compileAll();
        }

        return ApiResponse.success(Map.of(
            "sync", syncResult,
            "compile", compileResult != null ? compileResult : "skipped due to sync failure"
        ));
    }

    /**
     * Async sync and compile (returns immediately)
     */
    @PostMapping("/sync-and-compile/async")
    public ApiResponse<String> syncAndCompileAsync() {
        log.info("Async i18n sync and compile triggered");
        i18nSyncService.syncAndCompileAsync();
        return ApiResponse.success("Sync and compile started in background");
    }

    // ==================== Orphan Key Detection ====================

    /**
     * Scan for orphan i18n keys — translation entries whose DSL source entity
     * (e.g. model) no longer exists in the system.
     *
     * <p>Only {@code model.*} prefix keys are analyzed. Other prefixes
     * (action.*, admin.*, page.*, field.*) are treated conservatively and excluded
     * from the scan to avoid false positives on system-level keys.
     *
     * @return scan result with total scanned count, orphan count, and orphan key list
     */
    @GetMapping("/orphan-keys")
    public ResponseEntity<OrphanKeyDetector.OrphanKeyScanResult> scanOrphanKeys() {
        Long tenantId = MetaContext.getCurrentTenantId();
        OrphanKeyDetector.OrphanKeyScanResult result = orphanKeyDetector.scan(tenantId);
        return ResponseEntity.ok(result);
    }

    /**
     * Delete all orphan i18n keys for the current tenant.
     *
     * <p>This is a destructive operation — deleted rows cannot be recovered.
     * The caller must pass {@code confirm=true} as an explicit safety gate.
     *
     * <p>The set of keys to delete is determined by a fresh scan at deletion time,
     * so the result is consistent even if models were recently deleted or restored.
     *
     * @param confirm must be {@code true} to proceed; returns HTTP 400 otherwise
     * @return map containing {@code deletedRows} count
     */
    @DeleteMapping("/orphan-keys")
    public ResponseEntity<Map<String, Integer>> deleteOrphanKeys(
        @RequestParam(defaultValue = "false") boolean confirm
    ) {
        if (!confirm) {
            log.warn("Orphan key deletion rejected — confirm=true not supplied");
            return ResponseEntity.badRequest().build();
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        OrphanKeyDetector.OrphanKeyScanResult scanResult = orphanKeyDetector.scan(tenantId);

        int deleted = 0;
        if (!scanResult.orphanKeys().isEmpty()) {
            deleted = orphanKeyDetector.deleteOrphans(tenantId, scanResult.orphanKeys());
        }

        log.info("Deleted {} orphan i18n key rows for tenant={}", deleted, tenantId);
        return ResponseEntity.ok(Map.of("deletedRows", deleted));
    }
}
