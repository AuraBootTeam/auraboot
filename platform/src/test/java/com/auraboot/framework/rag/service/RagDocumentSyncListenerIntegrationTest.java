package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for RagDocumentSyncListener.
 * Tests call syncToRag() / removeFromRag() directly (bypassing @Async proxy).
 * Verifies RAG state via JDBC (bypassing TenantLineInterceptor).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RagDocumentSyncListenerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RagDocumentSyncListener syncListener;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private FileService fileService;

    @BeforeEach
    public void setupMocks() {
        super.setupTenantContext();
        when(embeddingService.embedBatch(anyLong(), anyList(), anyString()))
                .thenAnswer(inv -> {
                    List<String> texts = inv.getArgument(1);
                    return texts.stream().map(t -> new float[1536]).toList();
                });
    }

    @Test
    @Order(1)
    @DisplayName("SYNC-01: Published document triggers RAG sync")
    void syncPublishedDocument() {
        String pid = insertDkDoc("published", "Sync Test Title", "Full document content for RAG.");

        syncListener.syncToRag(getTestTenant().getId(), "dk_document", pid);

        assertKbExists("Document Knowledge Base");
        assertRagDocExists(pid, "Sync Test Title");
        assertRagDocStatus(pid, "completed");
        assertRagChunksExist(pid);
    }

    @Test
    @Order(2)
    @DisplayName("SYNC-02: Draft document is NOT synced")
    void draftNotSynced() {
        String pid = insertDkDoc("draft", "Draft Title", "Draft content.");
        syncListener.syncToRag(getTestTenant().getId(), "dk_document", pid);
        assertNoRagDoc(pid);
    }

    @Test
    @Order(3)
    @DisplayName("SYNC-03: Updated content re-syncs")
    void updateResyncs() {
        String pid = insertDkDoc("published", "Update Test", "Original content.");
        syncListener.syncToRag(getTestTenant().getId(), "dk_document", pid);
        assertRagDocExists(pid, "Update Test");

        jdbcTemplate.update("UPDATE mt_dk_document SET dk_doc_content = ? WHERE pid = ?",
                "Updated content after edit.", pid);
        syncListener.syncToRag(getTestTenant().getId(), "dk_document", pid);

        // Verify chunk content updated
        String chunkContent = getFirstChunkContent(pid);
        assertThat(chunkContent).contains("Updated content");
    }

    @Test
    @Order(4)
    @DisplayName("SYNC-04: Unchanged content skips re-sync")
    void unchangedSkips() {
        String pid = insertDkDoc("published", "Skip Test", "Same content.");
        syncListener.syncToRag(getTestTenant().getId(), "dk_document", pid);
        syncListener.syncToRag(getTestTenant().getId(), "dk_document", pid);
        // Should have exactly 1 RAG doc (not 2)
        assertThat(countRagDocs(pid)).isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("SYNC-05: Delete removes RAG document")
    void deleteRemoves() {
        String pid = insertDkDoc("published", "Delete Test", "Will be deleted.");
        syncListener.syncToRag(getTestTenant().getId(), "dk_document", pid);
        assertRagDocExists(pid, "Delete Test");

        syncListener.removeFromRag(getTestTenant().getId(), pid);
        assertNoRagDoc(pid);
    }

    @Test
    @Order(6)
    @DisplayName("SYNC-06: PUBLISHED→ARCHIVED removes from RAG")
    void archivedRemoves() {
        String pid = insertDkDoc("published", "Archive Test", "Will be archived.");
        syncListener.syncToRag(getTestTenant().getId(), "dk_document", pid);
        assertRagDocExists(pid, "Archive Test");

        jdbcTemplate.update("UPDATE mt_dk_document SET dk_doc_status = 'archived' WHERE pid = ?", pid);
        syncListener.syncToRag(getTestTenant().getId(), "dk_document", pid);
        assertNoRagDoc(pid);
    }

    @Test
    @Order(7)
    @DisplayName("SYNC-07: Knowledge article syncs to RAG")
    void articleSyncs() {
        String pid = insertDkArticle("published", "Article Title", "Article content.");
        syncListener.syncToRag(getTestTenant().getId(), "dk_knowledge_article", pid);
        assertRagDocExists(pid, "Article Title");
    }

    @Test
    @Order(8)
    @DisplayName("SYNC-08: Non-syncable model is ignored")
    void nonSyncableIgnored() {
        CommandCompletedEvent event = new CommandCompletedEvent(
                getTestTenant().getId(), "pid", "crm_account", Map.of(), "create", "create");
        assertThatCode(() -> syncListener.onCommandCompleted(event)).doesNotThrowAnyException();
    }

    @Test
    @Order(9)
    @DisplayName("SYNC-09: Empty content is skipped")
    void emptyContentSkipped() {
        String pid = insertDkDoc("published", "Empty", "");
        syncListener.syncToRag(getTestTenant().getId(), "dk_document", pid);
        assertNoRagDoc(pid);
    }

    @Test
    @Order(10)
    @DisplayName("SYNC-10: Embedding failure stores chunks without vectors")
    void embeddingFailStoresChunks() {
        when(embeddingService.embedBatch(anyLong(), anyList(), anyString()))
                .thenThrow(new RuntimeException("API down"));

        String pid = insertDkDoc("published", "Embed Fail", "Content without embeddings.");
        syncListener.syncToRag(getTestTenant().getId(), "dk_document", pid);
        assertRagDocExists(pid, "Embed Fail");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String insertDkDoc(String status, String title, String content) {
        String pid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO mt_dk_document (pid, tenant_id, dk_doc_title, dk_doc_content, dk_doc_status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
                pid, getTestTenant().getId(), title, content, status);
        return pid;
    }

    private String insertDkArticle(String status, String title, String content) {
        String pid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO mt_dk_knowledge_article (pid, tenant_id, dk_ka_title, dk_ka_content, dk_ka_status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
                pid, getTestTenant().getId(), title, content, status);
        return pid;
    }

    private void assertKbExists(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_knowledge_base WHERE name = ?", Integer.class, name);
        assertThat(count).as("KB '%s' should exist", name).isGreaterThan(0);
    }

    private static final String ACTIVE_DOC_FILTER =
            "source_entity_id = ? AND source_type = 'entity' AND (deleted_flag = FALSE OR deleted_flag IS NULL)";

    private void assertRagDocExists(String sourceEntityId, String expectedName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_kb_document WHERE " + ACTIVE_DOC_FILTER,
                Integer.class, sourceEntityId);
        assertThat(count).as("RAG doc for entity %s should exist", sourceEntityId).isGreaterThan(0);
    }

    private void assertRagDocStatus(String sourceEntityId, String expectedStatus) {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM ab_kb_document WHERE " + ACTIVE_DOC_FILTER,
                String.class, sourceEntityId);
        assertThat(status).isEqualTo(expectedStatus);
    }

    private void assertRagChunksExist(String sourceEntityId) {
        String docPid = jdbcTemplate.queryForObject(
                "SELECT pid FROM ab_kb_document WHERE " + ACTIVE_DOC_FILTER,
                String.class, sourceEntityId);
        Integer chunkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_kb_chunk WHERE doc_id = ?", Integer.class, docPid);
        assertThat(chunkCount).isGreaterThan(0);
    }

    private void assertNoRagDoc(String sourceEntityId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_kb_document WHERE " + ACTIVE_DOC_FILTER,
                Integer.class, sourceEntityId);
        assertThat(count).isZero();
    }

    private int countRagDocs(String sourceEntityId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_kb_document WHERE " + ACTIVE_DOC_FILTER,
                Integer.class, sourceEntityId);
    }

    private String getFirstChunkContent(String sourceEntityId) {
        String docPid = jdbcTemplate.queryForObject(
                "SELECT pid FROM ab_kb_document WHERE " + ACTIVE_DOC_FILTER + " LIMIT 1",
                String.class, sourceEntityId);
        return jdbcTemplate.queryForObject(
                "SELECT content FROM ab_kb_chunk WHERE doc_id = ? ORDER BY chunk_index LIMIT 1",
                String.class, docPid);
    }
}
