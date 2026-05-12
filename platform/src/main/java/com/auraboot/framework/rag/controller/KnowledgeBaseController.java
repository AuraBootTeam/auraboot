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
import com.auraboot.framework.rag.service.KnowledgeBaseService;
import com.auraboot.framework.rag.service.RagRetrievalService;
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
    private final RagRetrievalService ragRetrievalService;
    private final DocGenerationService docGenerationService;
    private final InternalDocImportService internalDocImportService;
    private final FileService fileService;

    // =========================================================================
    // Knowledge Base CRUD
    // =========================================================================

    @GetMapping
    public ApiResponse<List<KnowledgeBaseDTO>> list() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(kbService.listKnowledgeBases(tenantId));
    }

    @GetMapping("/{kbPid}")
    public ApiResponse<KnowledgeBaseDTO> get(@PathVariable String kbPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        KnowledgeBaseDTO kb = kbService.getKnowledgeBase(tenantId, kbPid);
        if (kb == null) return ApiResponse.error("Knowledge base not found");
        return ApiResponse.success(kb);
    }

    @PostMapping
    public ApiResponse<KnowledgeBaseDTO> create(@RequestBody CreateKnowledgeBaseRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        return ApiResponse.success(kbService.createKnowledgeBase(tenantId, userId, request));
    }

    @PutMapping("/{kbPid}")
    public ApiResponse<KnowledgeBaseDTO> update(@PathVariable String kbPid,
                                                  @RequestBody CreateKnowledgeBaseRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        KnowledgeBaseDTO updated = kbService.updateKnowledgeBase(tenantId, userId, kbPid, request);
        if (updated == null) return ApiResponse.error("Knowledge base not found");
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{kbPid}")
    public ApiResponse<Boolean> delete(@PathVariable String kbPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(kbService.deleteKnowledgeBase(tenantId, kbPid));
    }

    @PostMapping("/{kbPid}/toggle-status")
    public ApiResponse<Boolean> toggleStatus(@PathVariable String kbPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(kbService.toggleStatus(tenantId, kbPid));
    }

    // =========================================================================
    // Document management
    // =========================================================================

    @GetMapping("/{kbPid}/documents")
    public ApiResponse<List<KbDocumentDTO>> listDocuments(@PathVariable String kbPid) {
        return ApiResponse.success(kbService.listDocuments(kbPid));
    }

    @PostMapping("/{kbPid}/documents/upload")
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
    public ApiResponse<Boolean> deleteDocument(@PathVariable String kbPid,
                                                 @PathVariable String docPid) {
        return ApiResponse.success(kbService.deleteDocument(kbPid, docPid));
    }

    // =========================================================================
    // Chunk preview
    // =========================================================================

    @GetMapping("/{kbPid}/documents/{docPid}/chunks")
    public ApiResponse<List<KbChunk>> listChunks(@PathVariable String kbPid,
                                                   @PathVariable String docPid,
                                                   @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(kbService.listChunks(docPid, limit));
    }

    // =========================================================================
    // Retrieval test
    // =========================================================================

    @PostMapping("/retrieve")
    public ApiResponse<List<RetrievalResult>> retrieve(@RequestBody Map<String, Object> request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String query = (String) request.get("query");
        @SuppressWarnings("unchecked")
        List<String> kbPids = (List<String>) request.get("knowledgeBaseIds");
        Integer topK = request.get("topK") != null ? ((Number) request.get("topK")).intValue() : null;
        Double threshold = request.get("threshold") != null ? ((Number) request.get("threshold")).doubleValue() : null;

        List<RetrievalResult> results = ragRetrievalService.retrieve(tenantId, query, kbPids, topK, threshold);
        return ApiResponse.success(results);
    }


    // Internal docs import
    @PostMapping("/import-internal-docs")
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
    public ApiResponse<DocGenerationService.GenerationResult> generateDocs(
            @RequestBody Map<String, String> request) throws Exception {
        String outputDir = request.getOrDefault("outputDir", "docs/auto-generated");
        java.nio.file.Path resolved = resolveWorkspacePath(outputDir, "generated docs output path");
        return ApiResponse.success(docGenerationService.generate(resolved.toString()));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String resolveDocType(String extension) {
        if (extension == null) return null;
        return switch (extension.toLowerCase().replaceFirst("^\\.", "")) {
            case "pdf" -> "pdf";
            case "docx" -> "docx";
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
