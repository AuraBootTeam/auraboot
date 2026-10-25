package com.auraboot.framework.rag.service;

import com.auraboot.framework.application.tenant.MetaContext;
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
import com.auraboot.framework.rag.util.CjkBigramSegmenter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import com.auraboot.framework.exception.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CRUD service for knowledge bases, documents, and chunks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private static final int STORAGE_VECTOR_DIMENSION = 1536;

    private final KnowledgeBaseMapper kbMapper;
    private final KbDocumentMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final JdbcTemplate jdbcTemplate;
    private final com.auraboot.framework.cloudconfig.service.CloudConfigService cloudConfigService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final KnowledgeLineageService lineageService;

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

    @Transactional
    public KnowledgeBaseDTO createKnowledgeBase(Long tenantId, Long userId, CreateKnowledgeBaseRequest req) {
        ResolvedEmbeddingProvider resolved = resolveEmbeddingProvider(tenantId, req.getEmbeddingProvider());
        int requestedDimension = req.getEmbeddingDimension() != null
                ? req.getEmbeddingDimension()
                : resolved.dimension();
        if (requestedDimension != STORAGE_VECTOR_DIMENSION
                || requestedDimension != resolved.dimension()) {
            throw new BusinessException(
                    "Embedding dimension must match both the configured profile and vector storage width "
                            + STORAGE_VECTOR_DIMENSION + "; requested=" + requestedDimension
                            + ", configured=" + resolved.dimension());
        }
        KnowledgeBase kb = KnowledgeBase.builder()
                .pid(UniqueIdGenerator.generate())
                .tenantId(tenantId)
                .name(req.getName())
                .description(req.getDescription())
                .status("active")
                .visibility(normalizeVisibility(req.getVisibility()))
                .embeddingProvider(resolved.provider())
                .embeddingModel(hasText(req.getEmbeddingModel())
                        ? req.getEmbeddingModel().trim()
                        : resolved.model())
                .embeddingDimension(requestedDimension)
                .chunkStrategy("fixed_size")
                .chunkSize(req.getChunkSize() != null ? req.getChunkSize() : 500)
                .chunkOverlap(req.getChunkOverlap() != null ? req.getChunkOverlap() : 50)
                .docCount(0)
                .chunkCount(0)
                .createdBy(userId)
                .updatedBy(userId)
                .build();
        kbMapper.insert(kb);
        kb.setActiveIndexReleasePid(lineageService.createInitialRelease(kb));
        log.info("Created knowledge base: pid={}, name={}", kb.getPid(), kb.getName());
        return toDTO(kb);
    }

    /** Which embedding provider a new knowledge base will actually run on, and its model. */
    record ResolvedEmbeddingProvider(String provider, String model, int dimension) {}

    public record EmbeddingProfile(
            String providerCode,
            String displayName,
            String defaultModel,
            int dimensions) {
    }

    public List<EmbeddingProfile> listEmbeddingProfiles(Long tenantId) {
        List<com.auraboot.framework.cloudconfig.entity.CloudConfig> enabled =
                cloudConfigService.getEnabledProviders(tenantId, "embedding");
        if (enabled == null) {
            return List.of();
        }
        List<EmbeddingProfile> profiles = new java.util.ArrayList<>();
        for (com.auraboot.framework.cloudconfig.entity.CloudConfig cc : enabled) {
            if (cc.getProviderCode() == null || cc.getProviderCode().isBlank()
                    || cc.getConfig() == null) {
                continue;
            }
            try {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(cc.getConfig());
                String model = node.path("defaultModel").asText(null);
                int dimension = node.path("dimensions").asInt(STORAGE_VECTOR_DIMENSION);
                if (model == null || model.isBlank()
                        || dimension != STORAGE_VECTOR_DIMENSION) {
                    continue;
                }
                profiles.add(new EmbeddingProfile(
                        cc.getProviderCode(),
                        node.path("displayName").asText(cc.getProviderCode()),
                        model,
                        dimension));
            } catch (Exception e) {
                log.warn("Ignoring invalid embedding profile {}", cc.getProviderCode());
            }
        }
        return List.copyOf(profiles);
    }

    /**
     * Decide the embedding provider at creation time, and refuse one this deployment
     * cannot use.
     *
     * <p>The old default was the literal {@code "openai"}. EmbeddingService already
     * auto-resolves the first enabled provider — but only when the code is blank
     * (#1390 F3), and the create dialog always sent a non-blank 'openai'. So the
     * fallback never ran, the lookup went to a provider with no credentials, and every
     * chunk failed to embed. The failure was reported honestly downstream (a red
     * {@code 0/N} badge, {@code path=keyword}), which made it survivable but no less
     * of a dead end: the user was walked into it by the default.
     *
     * <p>So: blank means auto-resolve, and an explicitly named provider that is not
     * enabled is refused up front rather than baked into a knowledge base that can
     * never embed. Failing at creation costs one error message; failing later costs a
     * knowledge base that looks fine and answers nothing (§8 — configuration errors
     * report, they do not self-heal).
     */
    ResolvedEmbeddingProvider resolveEmbeddingProvider(Long tenantId, String requested) {
        List<com.auraboot.framework.cloudconfig.entity.CloudConfig> enabled =
                cloudConfigService.getEnabledProviders(tenantId, "embedding");
        List<String> codes = enabled == null ? List.of()
                : enabled.stream().map(com.auraboot.framework.cloudconfig.entity.CloudConfig::getProviderCode)
                        .filter(c -> c != null && !c.isBlank()).toList();

        if (requested != null && !requested.isBlank()) {
            if (!codes.contains(requested)) {
                throw new BusinessException(
                        "Embedding provider '" + requested + "' is not enabled for this deployment"
                                + (codes.isEmpty()
                                        ? " and no embedding provider is configured at all."
                                        : ". Available: " + String.join(", ", codes) + "."));
            }
            return resolvedProvider(enabled, requested);
        }

        // Blank: take the first enabled provider, the same order EmbeddingService uses.
        if (codes.isEmpty()) {
            throw new BusinessException(
                    "No embedding provider is configured for this deployment; a knowledge base "
                            + "created now could not embed anything.");
        }
        return resolvedProvider(enabled, codes.get(0));
    }

    private ResolvedEmbeddingProvider resolvedProvider(
            List<com.auraboot.framework.cloudconfig.entity.CloudConfig> enabled,
            String providerCode) {
        String model = defaultModelOf(enabled, providerCode);
        if (model == null) {
            throw new BusinessException(
                    "Embedding provider '" + providerCode
                            + "' has no valid defaultModel configuration");
        }
        int dimension = configuredDimensionOf(enabled, providerCode);
        if (dimension != STORAGE_VECTOR_DIMENSION) {
            throw new BusinessException(
                    "Embedding provider '" + providerCode + "' is configured for "
                            + dimension + " dimensions, but storage requires "
                            + STORAGE_VECTOR_DIMENSION);
        }
        return new ResolvedEmbeddingProvider(providerCode, model, dimension);
    }

    /**
     * The provider's own default model. Carrying the previous provider's model across
     * yields a "model not found" at the first embed, long after creation — the same trap
     * the create dialog handles for its dropdown.
     */
    private String defaultModelOf(List<com.auraboot.framework.cloudconfig.entity.CloudConfig> enabled,
                                  String providerCode) {
        if (enabled != null) {
            for (com.auraboot.framework.cloudconfig.entity.CloudConfig cc : enabled) {
                if (!providerCode.equals(cc.getProviderCode()) || cc.getConfig() == null) {
                    continue;
                }
                try {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(cc.getConfig());
                    String model = node.path("defaultModel").asText(null);
                    if (model != null && !model.isBlank()) return model;
                } catch (Exception e) {
                    log.debug("Could not read defaultModel for {}: {}", providerCode, e.getMessage());
                }
            }
        }
        return null;
    }

    private int configuredDimensionOf(
            List<com.auraboot.framework.cloudconfig.entity.CloudConfig> enabled,
            String providerCode) {
        if (enabled != null) {
            for (com.auraboot.framework.cloudconfig.entity.CloudConfig cc : enabled) {
                if (!providerCode.equals(cc.getProviderCode()) || cc.getConfig() == null) {
                    continue;
                }
                try {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(cc.getConfig());
                    int dimension = node.path("dimensions").asInt(0);
                    return dimension > 0 ? dimension : STORAGE_VECTOR_DIMENSION;
                } catch (Exception e) {
                    throw new BusinessException(
                            "Embedding provider '" + providerCode
                                    + "' has invalid configuration");
                }
            }
        }
        return STORAGE_VECTOR_DIMENSION;
    }

    public KnowledgeBaseDTO updateKnowledgeBase(Long tenantId, Long userId, String kbPid,
                                                  CreateKnowledgeBaseRequest req) {
        KnowledgeBase kb = findKb(tenantId, kbPid);
        if (kb == null) return null;

        if (req.getName() != null) kb.setName(req.getName());
        if (req.getDescription() != null) kb.setDescription(req.getDescription());
        if (req.getVisibility() != null) {
            kb.setVisibility(normalizeVisibility(req.getVisibility()));
        }
        boolean changesEmbeddingProfile =
                req.getEmbeddingProvider() != null
                        || req.getEmbeddingModel() != null
                        || req.getEmbeddingDimension() != null;
        if (changesEmbeddingProfile) {
            ResolvedEmbeddingProvider resolved = resolveEmbeddingProvider(
                    tenantId,
                    req.getEmbeddingProvider() != null
                            ? req.getEmbeddingProvider()
                            : kb.getEmbeddingProvider());
            String nextModel = hasText(req.getEmbeddingModel())
                    ? req.getEmbeddingModel().trim()
                    : hasText(kb.getEmbeddingModel())
                            ? kb.getEmbeddingModel()
                            : resolved.model();
            int nextDimension = req.getEmbeddingDimension() != null
                    ? req.getEmbeddingDimension() : kb.getEmbeddingDimension();
            boolean materialChange =
                    !java.util.Objects.equals(kb.getEmbeddingProvider(), resolved.provider())
                            || !java.util.Objects.equals(kb.getEmbeddingModel(), nextModel)
                            || !java.util.Objects.equals(kb.getEmbeddingDimension(), nextDimension);
            if (materialChange && kb.getChunkCount() != null && kb.getChunkCount() > 0) {
                throw new BusinessException(
                        "Embedding profile cannot be changed in place after indexing; "
                                + "create and activate a vector index release");
            }
            if (nextDimension != STORAGE_VECTOR_DIMENSION
                    || nextDimension != resolved.dimension()) {
                throw new BusinessException(
                        "Embedding dimension must match configured profile and storage width "
                                + STORAGE_VECTOR_DIMENSION);
            }
            kb.setEmbeddingProvider(resolved.provider());
            kb.setEmbeddingModel(nextModel);
            kb.setEmbeddingDimension(nextDimension);
        }
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

        // Cascade delete: chunks → docs → kb.
        // Defense-in-depth: include tenant_id predicate even though kbPid is
        // already tenant-scoped via findKb(...) above. Same pattern as the
        // other tenant-scoped tables — protects against future call paths
        // that bypass findKb(). See deep-review P3-2.
        jdbcTemplate.update("DELETE FROM ab_kb_chunk WHERE kb_id = ? AND tenant_id = ?", kbPid, tenantId);
        docMapper.delete(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getTenantId, tenantId)
                .eq(KbDocument::getKbId, kbPid));
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
        return listDocuments(MetaContext.getCurrentTenantId(), kbPid);
    }

    public List<KbDocumentDTO> listDocuments(Long tenantId, String kbPid) {
        if (findKb(tenantId, kbPid) == null) {
            return List.of();
        }
        List<KbDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KbDocument>()
                        .eq(KbDocument::getTenantId, tenantId)
                        .eq(KbDocument::getKbId, kbPid)
                        .orderByDesc(KbDocument::getCreatedAt));

        // How many of each document's chunks actually carry a vector.
        //
        // A document reports "completed" once its text is chunked and stored — embedding is a
        // separate, remote step that can fail on every single chunk while the document still goes
        // green. The user then has a knowledge base that looks perfect and cannot answer anything:
        // retrieval quietly falls back to keyword matching. The count is what makes that visible.
        //
        // Computed on read, never stored: the embedding retry pass repairs failed chunks in the
        // background, so a column written at ingest time would start lying within minutes.
        Map<String, Integer> embeddedByDoc = new HashMap<>();
        jdbcTemplate.query(
                "SELECT c.doc_id, COUNT(*) AS embedded FROM ab_kb_chunk c "
                + "JOIN ab_knowledge_base kb ON kb.tenant_id = c.tenant_id AND kb.pid = c.kb_id "
                + "WHERE c.tenant_id = ? AND c.kb_id = ? "
                + "AND c.index_release_pid = kb.active_index_release_pid "
                + "AND embedding_status = 'completed' GROUP BY doc_id",
                rs -> {
                    embeddedByDoc.put(rs.getString("doc_id"), rs.getInt("embedded"));
                },
                tenantId, kbPid);

        return docs.stream()
                .map(doc -> {
                    KbDocumentDTO dto = toDocDTO(doc);
                    dto.setEmbeddedChunkCount(embeddedByDoc.getOrDefault(doc.getPid(), 0));
                    return dto;
                })
                .toList();
    }

    public KbDocument createDocument(Long tenantId, Long userId, String kbPid,
                                      String docName, String docType, String filePid,
                                      Long fileSize, String sourceType, String sourceEntityId) {
        KnowledgeBase target = findKb(tenantId, kbPid);
        if (target == null || !"active".equals(target.getStatus())) {
            throw new BusinessException(
                    "Knowledge base is not active or does not belong to this tenant");
        }
        String normalizedDocType = normalizeDocTypeForStorage(docType);
        KbDocument doc = KbDocument.builder()
                .pid(UniqueIdGenerator.generate())
                .tenantId(tenantId)
                .kbId(kbPid)
                .filePid(filePid)
                .docName(docName)
                .docType(normalizedDocType)
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
                "UPDATE ab_knowledge_base SET doc_count = doc_count + 1, updated_at = NOW() "
                        + "WHERE pid = ? AND tenant_id = ?",
                kbPid, tenantId);
        doc.setDocType(formatDocTypeForDisplay(doc.getDocType()));
        return doc;
    }

    @Transactional
    public boolean deleteDocument(String kbPid, String docPid) {
        return deleteDocument(MetaContext.getCurrentTenantId(), kbPid, docPid);
    }

    @Transactional
    public boolean deleteDocument(Long tenantId, String kbPid, String docPid) {
        if (findKb(tenantId, kbPid) == null) {
            return false;
        }
        KbDocument doc = docMapper.selectOne(
                new LambdaQueryWrapper<KbDocument>()
                        .eq(KbDocument::getTenantId, tenantId)
                        .eq(KbDocument::getPid, docPid)
                        .eq(KbDocument::getKbId, kbPid));
        if (doc == null) return false;

        // Publish a new immutable snapshot without this document. Historical
        // releases/chunks remain available for audit evidence and index rollback.
        lineageService.removeDocumentFromActiveRelease(
                tenantId, kbPid, docPid, MetaContext.getCurrentUserId());
        docMapper.deleteById(doc.getId());

        refreshKbCounters(tenantId, kbPid);
        return true;
    }

    /**
     * Reset a document so it can be parsed again (manual retry of a failed or stranded document).
     * Clears the previous error and the reconcile attempt counter; the chunks left behind by the
     * failed run are cleared by the processing pipeline itself.
     *
     * @return false if no such document exists in this knowledge base
     */
    public boolean resetDocumentForReprocess(String kbPid, String docPid) {
        return resetDocumentForReprocess(MetaContext.getCurrentTenantId(), kbPid, docPid);
    }

    public boolean resetDocumentForReprocess(Long tenantId, String kbPid, String docPid) {
        if (findKb(tenantId, kbPid) == null) {
            return false;
        }
        KbDocument doc = docMapper.selectOne(
                new LambdaQueryWrapper<KbDocument>()
                        .eq(KbDocument::getTenantId, tenantId)
                        .eq(KbDocument::getPid, docPid)
                        .eq(KbDocument::getKbId, kbPid));
        if (doc == null) return false;

        jdbcTemplate.update(
                "UPDATE ab_kb_document SET status = 'pending', error_message = NULL, "
                + "process_retry_count = 0, process_started_at = NULL, process_completed_at = NULL "
                + "WHERE pid = ? AND tenant_id = ?",
                docPid, tenantId);
        return true;
    }

    /**
     * Update document status and counters after processing.
     */
    public void updateDocumentAfterProcessing(
            Long tenantId,
            String kbPid,
            String docPid,
            String status,
            int charCount,
            int chunkCount,
            String errorMessage) {
        jdbcTemplate.update(
                "UPDATE ab_kb_document SET status = ?, char_count = ?, chunk_count = ?, "
                + "error_message = ?, process_completed_at = NOW() "
                + "WHERE tenant_id = ? AND kb_id = ? AND pid = ?",
                status, charCount, chunkCount, errorMessage, tenantId, kbPid, docPid);
    }

    /**
     * Update KB chunk count (call after document processing).
     */
    public void refreshKbCounters(Long tenantId, String kbPid) {
        jdbcTemplate.update(
                "UPDATE ab_knowledge_base SET "
                + "doc_count = (SELECT COUNT(*) FROM ab_kb_document d "
                + "WHERE d.tenant_id = ? AND d.kb_id = ? "
                + "AND (d.deleted_flag IS NULL OR d.deleted_flag = FALSE)), "
                + "chunk_count = (SELECT COUNT(*) FROM ab_kb_chunk c "
                + "JOIN ab_kb_document d ON d.tenant_id = c.tenant_id "
                + "AND d.kb_id = c.kb_id AND d.pid = c.doc_id "
                + "WHERE c.tenant_id = ? AND c.kb_id = ? "
                + "AND c.index_release_pid = ab_knowledge_base.active_index_release_pid "
                + "AND (d.deleted_flag IS NULL OR d.deleted_flag = FALSE)) "
                + "WHERE tenant_id = ? AND pid = ?",
                tenantId, kbPid, tenantId, kbPid, tenantId, kbPid);
    }

    /**
     * Recompute every chunk's tsv with the current index-time segmentation
     * (G2: CJK bigrams). Needed once after upgrading rows ingested before the
     * segmenter existed; new ingests are segmented inline by the pipeline.
     *
     * @return number of chunks re-indexed, or -1 when the KB is unknown for this tenant
     */
    public int reindexChunkTsv(Long tenantId, String kbPid) {
        KnowledgeBase kb = findKb(tenantId, kbPid);
        if (kb == null) return -1;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, content FROM ab_kb_chunk WHERE kb_id = ? AND tenant_id = ?",
                kbPid, tenantId);
        for (Map<String, Object> row : rows) {
            jdbcTemplate.update(
                    "UPDATE ab_kb_chunk SET tsv = to_tsvector('simple', ?), updated_at = NOW() "
                            + "WHERE tenant_id = ? AND kb_id = ? AND pid = ?",
                    CjkBigramSegmenter.segment((String) row.get("content")),
                    tenantId, kbPid, row.get("pid"));
        }
        return rows.size();
    }

    // =========================================================================
    // Chunk operations
    // =========================================================================

    public List<KbChunk> listChunks(String docPid, int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return chunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getTenantId, tenantId)
                        .eq(KbChunk::getDocId, docPid)
                        .orderByAsc(KbChunk::getChunkIndex)
                        .last("LIMIT " + Math.min(limit, 200)));
    }

    public List<KbChunk> listChunks(Long tenantId, String kbPid, String docPid, int limit) {
        if (findKb(tenantId, kbPid) == null) {
            return List.of();
        }
        KbDocument doc = docMapper.selectOne(
                new LambdaQueryWrapper<KbDocument>()
                        .select(KbDocument::getId)
                        .eq(KbDocument::getTenantId, tenantId)
                        .eq(KbDocument::getKbId, kbPid)
                        .eq(KbDocument::getPid, docPid));
        if (doc == null) {
            return List.of();
        }
        KnowledgeBase kb = findKb(tenantId, kbPid);
        if (kb == null || kb.getActiveIndexReleasePid() == null) {
            return List.of();
        }
        return chunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getTenantId, tenantId)
                        .eq(KbChunk::getKbId, kbPid)
                        .eq(KbChunk::getDocId, docPid)
                        .eq(KbChunk::getIndexReleasePid, kb.getActiveIndexReleasePid())
                        .orderByAsc(KbChunk::getChunkIndex)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    KnowledgeBase findKb(Long tenantId, String kbPid) {
        return kbMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getTenantId, tenantId)
                        .eq(KnowledgeBase::getPid, kbPid));
    }

    public KnowledgeBase requireActiveKnowledgeBase(Long tenantId, String kbPid) {
        KnowledgeBase kb = findKb(tenantId, kbPid);
        if (kb == null || !"active".equals(kb.getStatus())) {
            throw new BusinessException(
                    "Knowledge base is not active or does not belong to this tenant");
        }
        return kb;
    }

    private KnowledgeBaseDTO toDTO(KnowledgeBase kb) {
        return KnowledgeBaseDTO.builder()
                .pid(kb.getPid())
                .name(kb.getName())
                .description(kb.getDescription())
                .status(kb.getStatus())
                .visibility(kb.getVisibility())
                .activeIndexReleasePid(kb.getActiveIndexReleasePid())
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

    private String normalizeVisibility(String visibility) {
        String normalized = visibility == null || visibility.isBlank()
                ? "tenant"
                : visibility.trim().toLowerCase(Locale.ROOT);
        if (!List.of("tenant", "restricted", "private").contains(normalized)) {
            throw new BusinessException("Invalid knowledge base visibility: " + visibility);
        }
        return normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private KbDocumentDTO toDocDTO(KbDocument doc) {
        return KbDocumentDTO.builder()
                .pid(doc.getPid())
                .kbId(doc.getKbId())
                .docName(doc.getDocName())
                .docType(formatDocTypeForDisplay(doc.getDocType()))
                .fileSize(doc.getFileSize())
                .charCount(doc.getCharCount())
                .chunkCount(doc.getChunkCount())
                .activeVersionPid(doc.getActiveVersionPid())
                .versionNo(doc.getVersionNo())
                .sourceType(doc.getSourceType())
                .status(doc.getStatus())
                .errorMessage(doc.getErrorMessage())
                .processStartedAt(doc.getProcessStartedAt())
                .processCompletedAt(doc.getProcessCompletedAt())
                .createdAt(doc.getCreatedAt())
                .build();
    }

    private String normalizeDocTypeForStorage(String docType) {
        return docType == null ? null : docType.toLowerCase(Locale.ROOT);
    }

    private String formatDocTypeForDisplay(String docType) {
        return docType == null ? null : docType.toUpperCase(Locale.ROOT);
    }
}
