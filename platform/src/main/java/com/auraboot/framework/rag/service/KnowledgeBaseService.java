package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.rag.dto.CreateKnowledgeBaseRequest;
import com.auraboot.framework.rag.dto.KbDocumentDTO;
import com.auraboot.framework.rag.dto.KnowledgeBaseDTO;
import com.auraboot.framework.rag.entity.KbChunk;
import com.auraboot.framework.rag.entity.KbDocument;
import com.auraboot.framework.rag.entity.KnowledgeBase;
import com.auraboot.framework.rag.mapper.KbChunkMapper;
import com.auraboot.framework.rag.mapper.KbDocumentMapper;
import com.auraboot.framework.rag.mapper.KnowledgeBaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD service for knowledge bases, documents, and chunks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper kbMapper;
    private final KbDocumentMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final JdbcTemplate jdbcTemplate;

    // =========================================================================
    // Knowledge Base CRUD
    // =========================================================================

    public List<KnowledgeBaseDTO> listKnowledgeBases(Long tenantId) {
        List<KnowledgeBase> list = kbMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getTenantId, tenantId)
                        .orderByDesc(KnowledgeBase::getCreatedAt));
        return list.stream().map(this::toDTO).toList();
    }

    public KnowledgeBaseDTO getKnowledgeBase(Long tenantId, String kbPid) {
        KnowledgeBase kb = findKb(tenantId, kbPid);
        return kb == null ? null : toDTO(kb);
    }

    public KnowledgeBaseDTO createKnowledgeBase(Long tenantId, Long userId, CreateKnowledgeBaseRequest req) {
        KnowledgeBase kb = KnowledgeBase.builder()
                .pid(UniqueIdGenerator.generate())
                .tenantId(tenantId)
                .name(req.getName())
                .description(req.getDescription())
                .status("active")
                .embeddingProvider(req.getEmbeddingProvider() != null ? req.getEmbeddingProvider() : "openai")
                .embeddingModel(req.getEmbeddingModel() != null ? req.getEmbeddingModel() : "text-embedding-3-small")
                .embeddingDimension(req.getEmbeddingDimension() != null ? req.getEmbeddingDimension() : 1536)
                .chunkStrategy("fixed_size")
                .chunkSize(req.getChunkSize() != null ? req.getChunkSize() : 500)
                .chunkOverlap(req.getChunkOverlap() != null ? req.getChunkOverlap() : 50)
                .docCount(0)
                .chunkCount(0)
                .createdBy(userId)
                .updatedBy(userId)
                .build();
        kbMapper.insert(kb);
        log.info("Created knowledge base: pid={}, name={}", kb.getPid(), kb.getName());
        return toDTO(kb);
    }

    public KnowledgeBaseDTO updateKnowledgeBase(Long tenantId, Long userId, String kbPid,
                                                  CreateKnowledgeBaseRequest req) {
        KnowledgeBase kb = findKb(tenantId, kbPid);
        if (kb == null) return null;

        if (req.getName() != null) kb.setName(req.getName());
        if (req.getDescription() != null) kb.setDescription(req.getDescription());
        if (req.getEmbeddingProvider() != null) kb.setEmbeddingProvider(req.getEmbeddingProvider());
        if (req.getEmbeddingModel() != null) kb.setEmbeddingModel(req.getEmbeddingModel());
        if (req.getEmbeddingDimension() != null) kb.setEmbeddingDimension(req.getEmbeddingDimension());
        if (req.getChunkSize() != null) kb.setChunkSize(req.getChunkSize());
        if (req.getChunkOverlap() != null) kb.setChunkOverlap(req.getChunkOverlap());
        kb.setUpdatedBy(userId);
        kbMapper.updateById(kb);
        return toDTO(kb);
    }

    @Transactional
    public boolean deleteKnowledgeBase(Long tenantId, String kbPid) {
        KnowledgeBase kb = findKb(tenantId, kbPid);
        if (kb == null) return false;

        // Cascade delete: chunks → docs → kb
        jdbcTemplate.update("DELETE FROM ab_kb_chunk WHERE kb_id = ?", kbPid);
        docMapper.delete(new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getKbId, kbPid));
        kbMapper.deleteById(kb.getId());
        log.info("Deleted knowledge base: pid={}", kbPid);
        return true;
    }

    public boolean toggleStatus(Long tenantId, String kbPid) {
        KnowledgeBase kb = findKb(tenantId, kbPid);
        if (kb == null) return false;
        kb.setStatus("active".equals(kb.getStatus()) ? "disabled" : "active");
        kbMapper.updateById(kb);
        return true;
    }

    // =========================================================================
    // Document operations
    // =========================================================================

    public List<KbDocumentDTO> listDocuments(String kbPid) {
        List<KbDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KbDocument>()
                        .eq(KbDocument::getKbId, kbPid)
                        .orderByDesc(KbDocument::getCreatedAt));
        return docs.stream().map(this::toDocDTO).toList();
    }

    public KbDocument createDocument(Long tenantId, Long userId, String kbPid,
                                      String docName, String docType, String filePid,
                                      Long fileSize, String sourceType, String sourceEntityId) {
        KbDocument doc = KbDocument.builder()
                .pid(UniqueIdGenerator.generate())
                .tenantId(tenantId)
                .kbId(kbPid)
                .filePid(filePid)
                .docName(docName)
                .docType(docType.toLowerCase())
                .fileSize(fileSize != null ? fileSize : 0L)
                .charCount(0)
                .chunkCount(0)
                .sourceType(sourceType != null ? sourceType : "file")
                .sourceEntityId(sourceEntityId)
                .status("pending")
                .createdBy(userId)
                .build();
        docMapper.insert(doc);
        // Update KB doc count
        jdbcTemplate.update(
                "UPDATE ab_knowledge_base SET doc_count = doc_count + 1, updated_at = NOW() WHERE pid = ?",
                kbPid);
        return doc;
    }

    @Transactional
    public boolean deleteDocument(String kbPid, String docPid) {
        KbDocument doc = docMapper.selectOne(
                new LambdaQueryWrapper<KbDocument>()
                        .eq(KbDocument::getPid, docPid)
                        .eq(KbDocument::getKbId, kbPid));
        if (doc == null) return false;

        // Delete chunks first
        int chunkCount = jdbcTemplate.update("DELETE FROM ab_kb_chunk WHERE doc_id = ?", docPid);
        docMapper.deleteById(doc.getId());

        // Update counters
        jdbcTemplate.update(
                "UPDATE ab_knowledge_base SET doc_count = GREATEST(doc_count - 1, 0), "
                + "chunk_count = GREATEST(chunk_count - ?, 0), updated_at = NOW() WHERE pid = ?",
                chunkCount, kbPid);
        return true;
    }

    /**
     * Update document status and counters after processing.
     */
    public void updateDocumentAfterProcessing(String docPid, String status, int charCount,
                                                int chunkCount, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE ab_kb_document SET status = ?, char_count = ?, chunk_count = ?, "
                + "error_message = ?, process_completed_at = NOW() WHERE pid = ?",
                status, charCount, chunkCount, errorMessage, docPid);
    }

    /**
     * Update KB chunk count (call after document processing).
     */
    public void refreshKbCounters(String kbPid) {
        jdbcTemplate.update(
                "UPDATE ab_knowledge_base SET "
                + "doc_count = (SELECT COUNT(*) FROM ab_kb_document WHERE kb_id = ? AND (deleted_flag IS NULL OR deleted_flag = FALSE)), "
                + "chunk_count = (SELECT COUNT(*) FROM ab_kb_chunk WHERE kb_id = ?) "
                + "WHERE pid = ?",
                kbPid, kbPid, kbPid);
    }

    // =========================================================================
    // Chunk operations
    // =========================================================================

    public List<KbChunk> listChunks(String docPid, int limit) {
        return chunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getDocId, docPid)
                        .orderByAsc(KbChunk::getChunkIndex)
                        .last("LIMIT " + Math.min(limit, 200)));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private KnowledgeBase findKb(Long tenantId, String kbPid) {
        return kbMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getTenantId, tenantId)
                        .eq(KnowledgeBase::getPid, kbPid));
    }

    KnowledgeBase findKbByPid(String kbPid) {
        return kbMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getPid, kbPid));
    }

    private KnowledgeBaseDTO toDTO(KnowledgeBase kb) {
        return KnowledgeBaseDTO.builder()
                .pid(kb.getPid())
                .name(kb.getName())
                .description(kb.getDescription())
                .status(kb.getStatus())
                .embeddingProvider(kb.getEmbeddingProvider())
                .embeddingModel(kb.getEmbeddingModel())
                .embeddingDimension(kb.getEmbeddingDimension())
                .chunkStrategy(kb.getChunkStrategy())
                .chunkSize(kb.getChunkSize())
                .chunkOverlap(kb.getChunkOverlap())
                .docCount(kb.getDocCount())
                .chunkCount(kb.getChunkCount())
                .createdAt(kb.getCreatedAt())
                .build();
    }

    private KbDocumentDTO toDocDTO(KbDocument doc) {
        return KbDocumentDTO.builder()
                .pid(doc.getPid())
                .kbId(doc.getKbId())
                .docName(doc.getDocName())
                .docType(doc.getDocType())
                .fileSize(doc.getFileSize())
                .charCount(doc.getCharCount())
                .chunkCount(doc.getChunkCount())
                .sourceType(doc.getSourceType())
                .status(doc.getStatus())
                .errorMessage(doc.getErrorMessage())
                .processStartedAt(doc.getProcessStartedAt())
                .processCompletedAt(doc.getProcessCompletedAt())
                .createdAt(doc.getCreatedAt())
                .build();
    }
}
