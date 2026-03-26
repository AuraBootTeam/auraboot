package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.dto.CreateKnowledgeBaseRequest;
import com.auraboot.framework.rag.dto.KbDocumentDTO;
import com.auraboot.framework.rag.dto.KnowledgeBaseDTO;
import com.auraboot.framework.rag.entity.KbChunk;
import com.auraboot.framework.rag.entity.KbDocument;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for KnowledgeBaseService — CRUD for KB, Document, and Chunk.
 * Tests use real PostgreSQL (with @Transactional + @Rollback from BaseIntegrationTest).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeBaseServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =========================================================================
    // Knowledge Base CRUD
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("KB-01: Create knowledge base with defaults")
    void createKnowledgeBase_withDefaults() {
        CreateKnowledgeBaseRequest req = new CreateKnowledgeBaseRequest();
        req.setName("Test KB " + System.currentTimeMillis());
        req.setDescription("Integration test knowledge base");

        KnowledgeBaseDTO dto = kbService.createKnowledgeBase(
                getTestTenant().getId(), getTestUser().getId(), req);

        assertThat(dto).isNotNull();
        assertThat(dto.getPid()).isNotBlank();
        assertThat(dto.getName()).isEqualTo(req.getName());
        assertThat(dto.getDescription()).isEqualTo(req.getDescription());
        assertThat(dto.getStatus()).isEqualTo("active");
        assertThat(dto.getEmbeddingProvider()).isEqualTo("openai");
        assertThat(dto.getEmbeddingModel()).isEqualTo("text-embedding-3-small");
        assertThat(dto.getEmbeddingDimension()).isEqualTo(1536);
        assertThat(dto.getChunkStrategy()).isEqualTo("fixed_size");
        assertThat(dto.getChunkSize()).isEqualTo(500);
        assertThat(dto.getChunkOverlap()).isEqualTo(50);
        assertThat(dto.getDocCount()).isZero();
        assertThat(dto.getChunkCount()).isZero();
        assertThat(dto.getCreatedAt()).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("KB-02: Create knowledge base with custom settings")
    void createKnowledgeBase_customSettings() {
        CreateKnowledgeBaseRequest req = new CreateKnowledgeBaseRequest();
        req.setName("Custom KB " + System.currentTimeMillis());
        req.setEmbeddingProvider("zhipu");
        req.setEmbeddingModel("embedding-2");
        req.setEmbeddingDimension(1024);
        req.setChunkSize(1000);
        req.setChunkOverlap(100);

        KnowledgeBaseDTO dto = kbService.createKnowledgeBase(
                getTestTenant().getId(), getTestUser().getId(), req);

        assertThat(dto.getEmbeddingProvider()).isEqualTo("zhipu");
        assertThat(dto.getEmbeddingModel()).isEqualTo("embedding-2");
        assertThat(dto.getEmbeddingDimension()).isEqualTo(1024);
        assertThat(dto.getChunkSize()).isEqualTo(1000);
        assertThat(dto.getChunkOverlap()).isEqualTo(100);
    }

    @Test
    @Order(3)
    @DisplayName("KB-03: List knowledge bases for tenant")
    void listKnowledgeBases() {
        // Create 2 KBs
        createTestKb("List Test A");
        createTestKb("List Test B");

        List<KnowledgeBaseDTO> list = kbService.listKnowledgeBases(getTestTenant().getId());

        assertThat(list).hasSizeGreaterThanOrEqualTo(2);
        assertThat(list).extracting(KnowledgeBaseDTO::getName)
                .anyMatch(n -> n.startsWith("List Test A"))
                .anyMatch(n -> n.startsWith("List Test B"));
    }

    @Test
    @Order(4)
    @DisplayName("KB-04: Get knowledge base by PID")
    void getKnowledgeBase() {
        KnowledgeBaseDTO created = createTestKb("Get Test");

        KnowledgeBaseDTO found = kbService.getKnowledgeBase(
                getTestTenant().getId(), created.getPid());

        assertThat(found).isNotNull();
        assertThat(found.getPid()).isEqualTo(created.getPid());
        assertThat(found.getName()).isEqualTo(created.getName());
    }

    @Test
    @Order(5)
    @DisplayName("KB-05: Get non-existent KB returns null")
    void getKnowledgeBase_notFound() {
        KnowledgeBaseDTO found = kbService.getKnowledgeBase(
                getTestTenant().getId(), "non-existent-pid");

        assertThat(found).isNull();
    }

    @Test
    @Order(6)
    @DisplayName("KB-06: Update knowledge base")
    void updateKnowledgeBase() {
        KnowledgeBaseDTO created = createTestKb("Update Test");

        CreateKnowledgeBaseRequest update = new CreateKnowledgeBaseRequest();
        update.setName("Updated Name " + System.currentTimeMillis());
        update.setDescription("Updated description");
        update.setChunkSize(800);

        KnowledgeBaseDTO updated = kbService.updateKnowledgeBase(
                getTestTenant().getId(), getTestUser().getId(), created.getPid(), update);

        assertThat(updated).isNotNull();
        assertThat(updated.getName()).isEqualTo(update.getName());
        assertThat(updated.getDescription()).isEqualTo("Updated description");
        assertThat(updated.getChunkSize()).isEqualTo(800);
        // Unchanged fields should remain
        assertThat(updated.getEmbeddingProvider()).isEqualTo("openai");
    }

    @Test
    @Order(7)
    @DisplayName("KB-07: Update non-existent KB returns null")
    void updateKnowledgeBase_notFound() {
        CreateKnowledgeBaseRequest update = new CreateKnowledgeBaseRequest();
        update.setName("Does not matter");

        KnowledgeBaseDTO result = kbService.updateKnowledgeBase(
                getTestTenant().getId(), getTestUser().getId(), "no-such-pid", update);

        assertThat(result).isNull();
    }

    @Test
    @Order(8)
    @DisplayName("KB-08: Toggle KB status ACTIVE → DISABLED → ACTIVE")
    void toggleStatus() {
        KnowledgeBaseDTO created = createTestKb("Toggle Test");
        assertThat(created.getStatus()).isEqualTo("active");

        // Toggle to DISABLED
        boolean toggled = kbService.toggleStatus(getTestTenant().getId(), created.getPid());
        assertThat(toggled).isTrue();
        KnowledgeBaseDTO afterFirst = kbService.getKnowledgeBase(
                getTestTenant().getId(), created.getPid());
        assertThat(afterFirst.getStatus()).isEqualTo("disabled");

        // Toggle back to ACTIVE
        kbService.toggleStatus(getTestTenant().getId(), created.getPid());
        KnowledgeBaseDTO afterSecond = kbService.getKnowledgeBase(
                getTestTenant().getId(), created.getPid());
        assertThat(afterSecond.getStatus()).isEqualTo("active");
    }

    @Test
    @Order(9)
    @DisplayName("KB-09: Delete knowledge base cascades to docs and chunks")
    void deleteKnowledgeBase_cascades() {
        KnowledgeBaseDTO created = createTestKb("Delete Test");

        // Create a document and chunk manually
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), created.getPid(),
                "test.txt", "txt", "file-pid-" + System.currentTimeMillis(), 100L,
                "file", null);
        insertTestChunk(created.getPid(), doc.getPid(), 0, "Test chunk content");

        // Delete KB
        boolean deleted = kbService.deleteKnowledgeBase(
                getTestTenant().getId(), created.getPid());
        assertThat(deleted).isTrue();

        // Verify KB is gone
        KnowledgeBaseDTO found = kbService.getKnowledgeBase(
                getTestTenant().getId(), created.getPid());
        assertThat(found).isNull();

        // Verify documents and chunks are also deleted
        List<KbDocumentDTO> docs = kbService.listDocuments(created.getPid());
        assertThat(docs).isEmpty();
    }

    @Test
    @Order(10)
    @DisplayName("KB-10: Tenant isolation — cannot see other tenant's KB")
    void tenantIsolation() {
        KnowledgeBaseDTO created = createTestKb("Isolation Test");

        // Try to access with a different tenant ID
        Long otherTenantId = getTestTenant().getId() + 9999;
        KnowledgeBaseDTO result = kbService.getKnowledgeBase(otherTenantId, created.getPid());
        assertThat(result).isNull();

        // Toggle should also fail for wrong tenant
        boolean toggled = kbService.toggleStatus(otherTenantId, created.getPid());
        assertThat(toggled).isFalse();
    }

    // =========================================================================
    // Document CRUD
    // =========================================================================

    @Test
    @Order(11)
    @DisplayName("DOC-01: Create document and verify counters")
    void createDocument() {
        KnowledgeBaseDTO kb = createTestKb("Doc Create Test");

        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "test-doc.md", "MD", "file-" + System.currentTimeMillis(), 2048L,
                "file", null);

        assertThat(doc).isNotNull();
        assertThat(doc.getPid()).isNotBlank();
        assertThat(doc.getKbId()).isEqualTo(kb.getPid());
        assertThat(doc.getDocName()).isEqualTo("test-doc.md");
        assertThat(doc.getDocType()).isEqualTo("MD");
        assertThat(doc.getStatus()).isEqualTo("pending");
        assertThat(doc.getSourceType()).isEqualTo("file");

        // Verify KB doc_count incremented
        KnowledgeBaseDTO updated = kbService.getKnowledgeBase(
                getTestTenant().getId(), kb.getPid());
        assertThat(updated.getDocCount()).isEqualTo(1);
    }

    @Test
    @Order(12)
    @DisplayName("DOC-02: List documents for KB")
    void listDocuments() {
        KnowledgeBaseDTO kb = createTestKb("Doc List Test");
        kbService.createDocument(getTestTenant().getId(), getTestUser().getId(),
                kb.getPid(), "doc-a.txt", "txt", "fp-a", 100L, "file", null);
        kbService.createDocument(getTestTenant().getId(), getTestUser().getId(),
                kb.getPid(), "doc-b.pdf", "pdf", "fp-b", 200L, "file", null);

        List<KbDocumentDTO> docs = kbService.listDocuments(kb.getPid());

        assertThat(docs).hasSize(2);
        assertThat(docs).extracting(KbDocumentDTO::getDocName)
                .containsExactlyInAnyOrder("doc-a.txt", "doc-b.pdf");
    }

    @Test
    @Order(13)
    @DisplayName("DOC-03: Delete document removes chunks and updates counters")
    void deleteDocument() {
        KnowledgeBaseDTO kb = createTestKb("Doc Delete Test");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "delete-me.txt", "txt", "fp-del", 50L, "file", null);

        // Insert some chunks
        insertTestChunk(kb.getPid(), doc.getPid(), 0, "chunk-0 content");
        insertTestChunk(kb.getPid(), doc.getPid(), 1, "chunk-1 content");
        kbService.refreshKbCounters(kb.getPid());

        // Verify counters before delete
        KnowledgeBaseDTO beforeDelete = kbService.getKnowledgeBase(
                getTestTenant().getId(), kb.getPid());
        assertThat(beforeDelete.getChunkCount()).isEqualTo(2);

        // Delete document
        boolean deleted = kbService.deleteDocument(kb.getPid(), doc.getPid());
        assertThat(deleted).isTrue();

        // Verify document gone
        List<KbDocumentDTO> docs = kbService.listDocuments(kb.getPid());
        assertThat(docs).isEmpty();

        // Verify counters decremented
        KnowledgeBaseDTO afterDelete = kbService.getKnowledgeBase(
                getTestTenant().getId(), kb.getPid());
        assertThat(afterDelete.getDocCount()).isZero();
        assertThat(afterDelete.getChunkCount()).isZero();
    }

    @Test
    @Order(14)
    @DisplayName("DOC-04: Delete non-existent document returns false")
    void deleteDocument_notFound() {
        KnowledgeBaseDTO kb = createTestKb("Doc NotFound Test");

        boolean result = kbService.deleteDocument(kb.getPid(), "no-such-doc");
        assertThat(result).isFalse();
    }

    @Test
    @Order(15)
    @DisplayName("DOC-05: Update document after processing")
    void updateDocumentAfterProcessing() {
        KnowledgeBaseDTO kb = createTestKb("Doc Process Test");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "process-me.txt", "txt", "fp-proc", 1000L, "file", null);

        // Simulate processing completion
        kbService.updateDocumentAfterProcessing(doc.getPid(), "completed", 5000, 10, null);

        // Verify via listing
        List<KbDocumentDTO> docs = kbService.listDocuments(kb.getPid());
        KbDocumentDTO updated = docs.stream()
                .filter(d -> d.getPid().equals(doc.getPid()))
                .findFirst().orElseThrow();

        assertThat(updated.getStatus()).isEqualTo("completed");
        assertThat(updated.getCharCount()).isEqualTo(5000);
        assertThat(updated.getChunkCount()).isEqualTo(10);
    }

    @Test
    @Order(16)
    @DisplayName("DOC-06: Update document with FAILED status preserves error message")
    void updateDocumentAfterProcessing_failed() {
        KnowledgeBaseDTO kb = createTestKb("Doc Fail Test");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "bad-file.xyz", "txt", "fp-fail", 0L, "file", null);

        kbService.updateDocumentAfterProcessing(
                doc.getPid(), "failed", 0, 0, "Unsupported format");

        List<KbDocumentDTO> docs = kbService.listDocuments(kb.getPid());
        KbDocumentDTO failed = docs.stream()
                .filter(d -> d.getPid().equals(doc.getPid()))
                .findFirst().orElseThrow();

        assertThat(failed.getStatus()).isEqualTo("failed");
        assertThat(failed.getErrorMessage()).isEqualTo("Unsupported format");
    }

    // =========================================================================
    // Chunk operations
    // =========================================================================

    @Test
    @Order(17)
    @DisplayName("CHUNK-01: List chunks ordered by index")
    void listChunks() {
        KnowledgeBaseDTO kb = createTestKb("Chunk List Test");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "chunked.txt", "txt", "fp-chunk", 100L, "file", null);

        insertTestChunk(kb.getPid(), doc.getPid(), 0, "First chunk");
        insertTestChunk(kb.getPid(), doc.getPid(), 1, "Second chunk");
        insertTestChunk(kb.getPid(), doc.getPid(), 2, "Third chunk");

        List<KbChunk> chunks = kbService.listChunks(doc.getPid(), 50);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getChunkIndex()).isEqualTo(0);
        assertThat(chunks.get(0).getContent()).isEqualTo("First chunk");
        assertThat(chunks.get(1).getChunkIndex()).isEqualTo(1);
        assertThat(chunks.get(2).getChunkIndex()).isEqualTo(2);
    }

    @Test
    @Order(18)
    @DisplayName("CHUNK-02: List chunks respects limit")
    void listChunks_limit() {
        KnowledgeBaseDTO kb = createTestKb("Chunk Limit Test");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "many-chunks.txt", "txt", "fp-many", 100L, "file", null);

        for (int i = 0; i < 10; i++) {
            insertTestChunk(kb.getPid(), doc.getPid(), i, "Chunk " + i);
        }

        List<KbChunk> limited = kbService.listChunks(doc.getPid(), 3);
        assertThat(limited).hasSize(3);
    }

    @Test
    @Order(19)
    @DisplayName("CHUNK-03: Refresh KB counters recalculates from actual data")
    void refreshKbCounters() {
        KnowledgeBaseDTO kb = createTestKb("Counter Refresh Test");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "counter.txt", "txt", "fp-cnt", 100L, "file", null);

        insertTestChunk(kb.getPid(), doc.getPid(), 0, "C0");
        insertTestChunk(kb.getPid(), doc.getPid(), 1, "C1");
        insertTestChunk(kb.getPid(), doc.getPid(), 2, "C2");

        kbService.refreshKbCounters(kb.getPid());

        KnowledgeBaseDTO refreshed = kbService.getKnowledgeBase(
                getTestTenant().getId(), kb.getPid());
        assertThat(refreshed.getDocCount()).isEqualTo(1);
        assertThat(refreshed.getChunkCount()).isEqualTo(3);
    }

    @Test
    @Order(20)
    @DisplayName("DOC-07: DocType is uppercased on create")
    void createDocument_docTypeUppercased() {
        KnowledgeBaseDTO kb = createTestKb("DocType Test");

        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "lowercase.md", "md", "fp-lower", 100L, "file", null);

        assertThat(doc.getDocType()).isEqualTo("MD");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private KnowledgeBaseDTO createTestKb(String namePrefix) {
        CreateKnowledgeBaseRequest req = new CreateKnowledgeBaseRequest();
        req.setName(namePrefix + " " + System.currentTimeMillis());
        req.setDescription("Test KB for " + namePrefix);
        return kbService.createKnowledgeBase(
                getTestTenant().getId(), getTestUser().getId(), req);
    }

    private void insertTestChunk(String kbPid, String docPid, int index, String content) {
        String chunkPid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_kb_chunk (pid, tenant_id, kb_id, doc_id, chunk_index, "
                + "content, char_count, token_count, tsv, embedding_status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, to_tsvector('simple', ?), 'pending', NOW(), NOW())",
                chunkPid, getTestTenant().getId(), kbPid, docPid,
                index, content, content.length(), content.length() / 4, content);
    }
}
