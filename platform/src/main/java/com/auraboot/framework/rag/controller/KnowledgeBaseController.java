package com.auraboot.framework.rag.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.PathSafetyUtils;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.rag.dto.*;
import com.auraboot.framework.rag.entity.KbChunk;
import com.auraboot.framework.rag.entity.KbDocument;
import com.auraboot.framework.rag.service.DocumentProcessingService;
import com.auraboot.framework.rag.service.DocGenerationService;
import com.auraboot.framework.rag.service.InternalDocImportService;
import com.auraboot.framework.rag.service.KbUrlIngestService;
import com.auraboot.framework.rag.service.KnowledgeBaseService;
import com.auraboot.framework.rag.service.RagRetrievalService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * REST controller for RAG knowledge base management.
 * Prefix: /api/ai/knowledge
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/knowledge")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final DocumentProcessingService docProcessingService;
    private final KbUrlIngestService urlIngestService;
    private final RagRetrievalService ragRetrievalService;
    private final DocGenerationService docGenerationService;
    private final InternalDocImportService internalDocImportService;
    private final FileService fileService;

    // =========================================================================
    // Knowledge Base CRUD
    // =========================================================================

    @GetMapping
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_READ)
    public ApiResponse<List<KnowledgeBaseDTO>> list() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(kbService.listKnowledgeBases(tenantId));
    }

    @GetMapping("/{kbPid}")
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_READ)
    public ApiResponse<KnowledgeBaseDTO> get(@PathVariable String kbPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        KnowledgeBaseDTO kb = kbService.getKnowledgeBase(tenantId, kbPid);
        if (kb == null) return ApiResponse.error("Knowledge base not found");
        return ApiResponse.success(kb);
    }

    @PostMapping
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_MANAGE)
    public ApiResponse<KnowledgeBaseDTO> create(@RequestBody CreateKnowledgeBaseRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        return ApiResponse.success(kbService.createKnowledgeBase(tenantId, userId, request));
    }

    @PutMapping("/{kbPid}")
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_MANAGE)
    public ApiResponse<KnowledgeBaseDTO> update(@PathVariable String kbPid,
                                                  @RequestBody CreateKnowledgeBaseRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        KnowledgeBaseDTO updated = kbService.updateKnowledgeBase(tenantId, userId, kbPid, request);
        if (updated == null) return ApiResponse.error("Knowledge base not found");
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{kbPid}")
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_MANAGE)
    public ApiResponse<Boolean> delete(@PathVariable String kbPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(kbService.deleteKnowledgeBase(tenantId, kbPid));
    }

    @PostMapping("/{kbPid}/toggle-status")
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_MANAGE)
    public ApiResponse<Boolean> toggleStatus(@PathVariable String kbPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(kbService.toggleStatus(tenantId, kbPid));
    }

    // =========================================================================
    // Document management
    // =========================================================================

    /**
     * Re-segment every chunk's tsv with the current CJK bigram segmentation (G2).
     * One-shot maintenance action for KBs ingested before the segmenter existed.
     */
    @PostMapping("/{kbPid}/reindex")
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_MANAGE)
    public ApiResponse<Map<String, Object>> reindex(@PathVariable String kbPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int count = kbService.reindexChunkTsv(tenantId, kbPid);
        if (count < 0) return ApiResponse.error("Knowledge base not found");
        return ApiResponse.success(Map.of("reindexedChunks", count));
    }

    @GetMapping("/{kbPid}/documents")
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_READ)
    public ApiResponse<List<KbDocumentDTO>> listDocuments(@PathVariable String kbPid) {
        return ApiResponse.success(kbService.listDocuments(kbPid));
    }

    @PostMapping("/{kbPid}/documents/upload")
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_MANAGE)
    public ApiResponse<KbDocumentDTO> uploadDocument(@PathVariable String kbPid,
                                                       @RequestParam("file") MultipartFile file) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        // 1. Upload file via FileService
        var uploadResult = fileService.uploadFile(file, userId);
        String filePid = uploadResult.getFileId(); // getFileId() returns the entity pid

        // 2. Resolve file entity for metadata
        FileEntity fileEntity = fileService.findByPid(filePid);

        // 3. Determine doc type from extension
        String ext = fileEntity != null ? fileEntity.getFileExtension() : null;
        String docType = resolveDocType(ext);
        if (docType == null) {
            return ApiResponse.error("Unsupported file type: " + ext);
        }
        KbDocument doc = kbService.createDocument(tenantId, userId, kbPid,
                uploadResult.getOriginalName(), docType, filePid,
                uploadResult.getFileSize(), "file", null);

        // 5. Trigger async processing
        docProcessingService.processDocument(kbPid, doc.getPid());

        return ApiResponse.success(KbDocumentDTO.builder()
                .pid(doc.getPid())
                .kbId(doc.getKbId())
                .docName(doc.getDocName())
                .docType(doc.getDocType())
                .status(doc.getStatus())
                .createdAt(doc.getCreatedAt())
                .build());
    }

    @DeleteMapping("/{kbPid}/documents/{docPid}")
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_MANAGE)
    public ApiResponse<Boolean> deleteDocument(@PathVariable String kbPid,
                                                 @PathVariable String docPid) {
        return ApiResponse.success(kbService.deleteDocument(kbPid, docPid));
    }

    /**
     * Add a web page to the knowledge base by URL.
     *
     * <p>This makes the backend fetch a URL the caller chose, so it is an SSRF sink: the fetch is
     * gated by {@code SsrfValidator} and sent with the resolved IP pinned. A rejected URL comes back
     * as a plain error the user can act on, not a 500.
     *
     * <p>One page, fetched now. Crawling a site is the crawler plugin's job.
     */
    @PostMapping("/{kbPid}/documents/from-url")
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_MANAGE)
    public ApiResponse<KbDocumentDTO> addDocumentFromUrl(@PathVariable String kbPid,
                                                           @RequestBody Map<String, String> request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String url = request.get("url");

        String docPid;
        try {
            docPid = urlIngestService.ingestUrl(tenantId, kbPid, url);
        } catch (IllegalArgumentException e) {
            // The URL was refused (unsafe target, unreachable host, not an HTML page, no text).
            // This is the user's input being wrong, not the server failing.
            return ApiResponse.error(e.getMessage());
        } catch (java.io.IOException e) {
            log.warn("Fetching {} for kb {} failed: {}", url, kbPid, e.getMessage());
            return ApiResponse.error("Could not fetch the URL: " + e.getMessage());
        }

        return ApiResponse.success(kbService.listDocuments(kbPid).stream()
                .filter(d -> docPid.equals(d.getPid()))
                .findFirst()
                .orElse(null));
    }

    /**
     * Parse a document again — for one that failed, or that a worker restart left stranded.
     * Without this the only way out of a failed parse is to delete the document and re-upload it.
     */
    @PostMapping("/{kbPid}/documents/{docPid}/reprocess")
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_MANAGE)
    public ApiResponse<Boolean> reprocessDocument(@PathVariable String kbPid,
                                                    @PathVariable String docPid) {
        if (!kbService.resetDocumentForReprocess(kbPid, docPid)) {
            return ApiResponse.error("Document not found: " + docPid);
        }
        docProcessingService.processDocument(kbPid, docPid);
        return ApiResponse.success(true);
    }

    // =========================================================================
    // Chunk preview
    // =========================================================================

    @GetMapping("/{kbPid}/documents/{docPid}/chunks")
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_READ)
    public ApiResponse<List<KbChunk>> listChunks(@PathVariable String kbPid,
                                                   @PathVariable String docPid,
                                                   @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(kbService.listChunks(docPid, limit));
    }

    // =========================================================================
    // Retrieval test
    // =========================================================================

    @PostMapping("/retrieve")
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_RETRIEVE)
    public ApiResponse<com.auraboot.framework.rag.dto.RetrievalOutcome> retrieve(@RequestBody Map<String, Object> request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String query = (String) request.get("query");
        @SuppressWarnings("unchecked")
        List<String> kbPids = (List<String>) request.get("knowledgeBaseIds");
        Integer topK = request.get("topK") != null ? ((Number) request.get("topK")).intValue() : null;
        Double threshold = request.get("threshold") != null ? ((Number) request.get("threshold")).doubleValue() : null;

        return ApiResponse.success(
                ragRetrievalService.retrieveWithDiagnostics(tenantId, query, kbPids, topK, threshold));
    }


    // Internal docs import
    @PostMapping("/import-internal-docs")
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_MANAGE)
    public ApiResponse<InternalDocImportService.ImportResult> importInternalDocs(
            @RequestBody Map<String, String> request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String docsPath = request.getOrDefault("path", "docs/system-reference");
        java.nio.file.Path resolved = resolveWorkspacePath(docsPath, "internal docs path");
        return ApiResponse.success(internalDocImportService.importDocs(tenantId, userId, resolved.toString()));
    }

    // =========================================================================
    // Auto-generated docs
    // =========================================================================

    @PostMapping("/generate-docs")
    @RequirePermission(MetaPermission.AI_KNOWLEDGE_MANAGE)
    public ApiResponse<DocGenerationService.GenerationResult> generateDocs(
            @RequestBody Map<String, String> request) throws Exception {
        String outputDir = request.getOrDefault("outputDir", "docs/auto-generated");
        java.nio.file.Path resolved = resolveWorkspacePath(outputDir, "generated docs output path");
        return ApiResponse.success(docGenerationService.generate(resolved.toString()));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Map an uploaded file's extension to a doc_type, or null if we cannot parse it.
     *
     * <p>This gate runs <b>before</b> the document row is created, so a format missing here is
     * rejected outright no matter what the parser and the CHECK constraint allow. Adding a format
     * means touching four places in lockstep: the {@code chk_doc_type} constraint, this switch,
     * {@link DocumentParserService#SUPPORTED_DOC_TYPES}, and the accept list in the upload UI.
     *
     * <p>Legacy binary Office formats (.ppt/.xls/.doc) are deliberately absent — parsing them
     * needs poi-scratchpad, which is not a dependency. Only OOXML is accepted.
     */
    static String resolveDocType(String extension) {
        if (extension == null) return null;
        return switch (extension.toLowerCase().replaceFirst("^\\.", "")) {
            case "pdf" -> "pdf";
            case "docx" -> "docx";
            case "pptx" -> "pptx";
            case "xlsx" -> "xlsx";
            case "md", "markdown" -> "md";
            case "txt" -> "txt";
            case "csv" -> "csv";
            case "html", "htm" -> "html";
            default -> null;
        };
    }

    private java.nio.file.Path resolveWorkspacePath(String path, String context) {
        java.nio.file.Path workspaceRoot = java.nio.file.Path.of(System.getProperty("user.dir"));
        return PathSafetyUtils.requireWithinBase(workspaceRoot, java.nio.file.Path.of(path), context);
    }
}
