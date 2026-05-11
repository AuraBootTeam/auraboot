package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.common.util.PathSafetyUtils;
import com.auraboot.framework.rag.dto.CreateKnowledgeBaseRequest;
import com.auraboot.framework.rag.dto.KnowledgeBaseDTO;
import com.auraboot.framework.rag.entity.KbDocument;
import com.auraboot.framework.rag.mapper.KbDocumentMapper;
import com.auraboot.framework.rag.util.VectorUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

/**
 * Batch import internal markdown docs into a RAG knowledge base.
 * Supports incremental updates via content SHA-256 hash comparison.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalDocImportService {

    private final KnowledgeBaseService kbService;
    private final KbDocumentMapper docMapper;
    private final DocumentParserService parserService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;

    private static final String INTERNAL_KB_NAME = "AuraBoot System Documentation";
    private static final String SOURCE_TYPE = "internal_doc";

    /**
     * Import all markdown files from a directory into a dedicated knowledge base.
     * Creates the KB if it doesn't exist. Skips unchanged files (by content hash).
     *
     * @param tenantId  current tenant
     * @param userId    importing user
     * @param docsPath  absolute path to docs directory (e.g., /path/to/docs/system-reference)
     * @return import summary
     */
    @Transactional
    public ImportResult importDocs(Long tenantId, Long userId, String docsPath) {
        Path basePath = PathSafetyUtils.requireExistingDirectory(Path.of(docsPath), "internal docs path");

        // 1. Ensure dedicated KB exists
        KnowledgeBaseDTO kb = ensureInternalKb(tenantId, userId);
        String kbPid = kb.getPid();

        // 2. Collect all .md files
        List<Path> mdFiles = collectMarkdownFiles(basePath);
        log.info("Found {} markdown files in {}", mdFiles.size(), docsPath);

        // 3. Load existing docs for this KB (for incremental comparison)
        Map<String, KbDocument> existingDocs = loadExistingDocs(kbPid);

        int imported = 0;
        int skipped = 0;
        int updated = 0;
        int failed = 0;

        for (Path file : mdFiles) {
            String relativePath = basePath.relativize(file).toString();
            try {
                String content = Files.readString(file);
                String hash = sha256(content);

                // Check if doc already exists with same hash
                KbDocument existing = existingDocs.get(relativePath);
                if (existing != null && hash.equals(existing.getContentHash())) {
                    skipped++;
                    continue;
                }

                if (existing != null) {
                    // Content changed — delete old chunks and re-import
                    deleteDocChunks(existing.getPid(), kbPid);
                    docMapper.deleteById(existing.getId());
                    updated++;
                } else {
                    imported++;
                }

                // Create document record
                KbDocument doc = KbDocument.builder()
                        .pid(UniqueIdGenerator.generate())
                        .tenantId(tenantId)
                        .kbId(kbPid)
                        .docName(relativePath)
                        .docType("md")
                        .fileSize((long) content.length())
                        .charCount(content.length())
                        .sourceType(SOURCE_TYPE)
                        .sourceEntityId(relativePath)
                        .contentHash(hash)
                        .status("processing")
                        .createdBy(userId)
                        .build();
                docMapper.insert(doc);

                // Chunk and store
                processContent(doc, content, kbPid, tenantId);

            } catch (Exception e) {
                log.error("Failed to import {}: {}", relativePath, e.getMessage());
                failed++;
            }
        }

        // Refresh counters
        kbService.refreshKbCounters(kbPid);

        ImportResult result = new ImportResult(kbPid, mdFiles.size(), imported, updated, skipped, failed);
        log.info("Import complete: {}", result);
        return result;
    }

    /**
     * Process document content: chunk → embed → store chunks.
     */
    private void processContent(KbDocument doc, String content, String kbPid, Long tenantId) {
        // 1. Chunk
        List<ChunkingService.ChunkResult> chunks = chunkingService.chunk(content, 500, 50);
        if (chunks.isEmpty()) {
            kbService.updateDocumentAfterProcessing(doc.getPid(), "failed", 0, 0, "No chunks produced");
            return;
        }

        // 2. Store chunks
        List<String> chunkPids = new ArrayList<>();
        List<String> chunkTexts = new ArrayList<>();
        for (ChunkingService.ChunkResult chunk : chunks) {
            String chunkPid = UniqueIdGenerator.generate();
            chunkPids.add(chunkPid);
            chunkTexts.add(chunk.content());

            // Store metadata with file path and section info
            String metadata = String.format("{\"filePath\":\"%s\",\"chunkIndex\":%d}",
                    doc.getDocName().replace("\"", "\\\""), chunk.index());

            jdbcTemplate.update(
                    "INSERT INTO ab_kb_chunk (pid, tenant_id, kb_id, doc_id, chunk_index, "
                    + "content, char_count, token_count, metadata, tsv, embedding_status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, to_tsvector('simple', ?), 'pending', NOW(), NOW())",
                    chunkPid, tenantId, kbPid, doc.getPid(),
                    chunk.index(), chunk.content(), chunk.charCount(), chunk.tokenCount(),
                    metadata, chunk.content());
        }

        // 3. Embed (best-effort — store chunks even if embedding fails)
        try {
            // Find KB to get embedding provider
            var kb = kbService.findKbByPid(kbPid);
            String provider = kb != null ? kb.getEmbeddingProvider() : "openai";

            List<float[]> embeddings = embeddingService.embedBatch(tenantId, chunkTexts, provider);
            for (int i = 0; i < embeddings.size() && i < chunkPids.size(); i++) {
                float[] emb = embeddings.get(i);
                if (emb != null) {
                    jdbcTemplate.update(
                            "UPDATE ab_kb_chunk SET embedding = ?::vector, embedding_status = 'completed', "
                            + "updated_at = NOW() WHERE pid = ?",
                            VectorUtils.toVectorString(emb), chunkPids.get(i));
                }
            }
        } catch (Exception e) {
            log.warn("Embedding failed for doc {}: {}", doc.getDocName(), e.getMessage());
        }

        // 4. Update document status
        kbService.updateDocumentAfterProcessing(doc.getPid(), "completed",
                content.length(), chunks.size(), null);
    }

    private KnowledgeBaseDTO ensureInternalKb(Long tenantId, Long userId) {
        List<KnowledgeBaseDTO> existing = kbService.listKnowledgeBases(tenantId);
        for (KnowledgeBaseDTO kb : existing) {
            if (INTERNAL_KB_NAME.equals(kb.getName())) {
                return kb;
            }
        }

        CreateKnowledgeBaseRequest req = new CreateKnowledgeBaseRequest();
        req.setName(INTERNAL_KB_NAME);
        req.setDescription("Auto-imported system documentation from docs/system-reference/");
        req.setChunkSize(500);
        req.setChunkOverlap(50);
        return kbService.createKnowledgeBase(tenantId, userId, req);
    }

    private List<Path> collectMarkdownFiles(Path basePath) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(basePath)) {
            walk.filter(p -> !Files.isDirectory(p))
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".md") || name.endsWith(".mdx");
                })
                .filter(p -> !p.getFileName().toString().equals("INDEX.md"))
                .sorted()
                .forEach(files::add);
        } catch (IOException e) {
            log.error("Failed to walk directory {}: {}", basePath, e.getMessage());
        }
        return files;
    }

    private Map<String, KbDocument> loadExistingDocs(String kbPid) {
        List<KbDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KbDocument>()
                        .eq(KbDocument::getKbId, kbPid)
                        .eq(KbDocument::getSourceType, SOURCE_TYPE));
        Map<String, KbDocument> map = new HashMap<>();
        for (KbDocument doc : docs) {
            // sourceEntityId stores the relative path for INTERNAL_DOC
            if (doc.getSourceEntityId() != null) {
                map.put(doc.getSourceEntityId(), doc);
            }
        }
        return map;
    }

    private void deleteDocChunks(String docPid, String kbPid) {
        jdbcTemplate.update("DELETE FROM ab_kb_chunk WHERE doc_id = ?", docPid);
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public record ImportResult(
            String kbPid,
            int totalFiles,
            int imported,
            int updated,
            int skipped,
            int failed
    ) {}
}
