package com.auraboot.framework.rag.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.dto.CreateKnowledgeBaseRequest;
import com.auraboot.framework.rag.dto.KnowledgeBaseDTO;
import com.auraboot.framework.rag.entity.KbDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * G2-5: documents stranded by a worker restart must be picked back up.
 *
 * <p>Document parsing runs on an in-process async executor with no durable queue. If the JVM dies
 * mid-parse the row is left in {@code processing} (or {@code pending}, if it never got picked up)
 * and, before this reconcile pass existed, nothing ever moved it again.
 *
 * <p>Each test <b>clears MetaContext before invoking the reconcile pass</b>, because that is the
 * one thing the scheduler thread genuinely does differently from every other caller in this
 * codebase: it has no HTTP request behind it, so nothing has populated the tenant context that the
 * ingest pipeline reads. A test that left the context in place would pass while production failed
 * with "MetaContext not initialized".
 */
@DisplayName("DocumentReconcileService")
class DocumentReconcileServiceIntegrationTest extends BaseIntegrationTest {

    /** pgvector column width — a shorter vector is rejected outright. */
    private static final int EMBEDDING_DIMENSIONS = 1536;

    @Autowired
    private DocumentReconcileService reconcileService;

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private FileService fileService;

    @MockitoBean
    private StorageProvider storageProvider;

    @BeforeEach
    void stubEmbeddingAndStorage() throws Exception {
        super.setupTenantContext();

        // The vector column is fixed at 1536 dimensions — a shorter vector is rejected by pgvector.
        when(embeddingService.embedBatch(anyLong(), anyList(), anyString()))
                .thenAnswer(inv -> {
                    List<String> texts = inv.getArgument(1);
                    return texts.stream().map(t -> {
                        float[] embedding = new float[EMBEDDING_DIMENSIONS];
                        Arrays.fill(embedding, 0.1f);
                        return embedding;
                    }).toList();
                });

        Path file = Files.createTempDirectory("rag-reconcile-").resolve("stranded.txt");
        Files.writeString(file, "The support hotline is open 09:00-18:00 on working days.");

        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileName(file.getFileName().toString());
        fileEntity.setLocalPath(file.toString());
        when(fileService.findByPid(anyString())).thenReturn(fileEntity);
        when(storageProvider.download(anyString()))
                .thenAnswer(inv -> Files.newInputStream(file));
    }

    @Test
    @DisplayName("reclaims a document a worker restart left in 'processing', with no MetaContext on the thread")
    void reclaimsStrandedProcessingDocument() {
        String docPid = createDocumentStuckIn("processing", 30);

        // The scheduler thread has no tenant context. This is the whole point of the test.
        MetaContext.clear();
        int recovered = reconcileService.reclaimStuckDocuments();

        assertThat(recovered).isEqualTo(1);

        Map<String, Object> row = documentRow(docPid);
        assertThat(row.get("status")).isEqualTo("completed");
        assertThat((Integer) row.get("process_retry_count")).isEqualTo(1);
        assertThat((Integer) row.get("chunk_count")).isGreaterThan(0);
        assertThat(chunkCount(docPid)).isGreaterThan(0);
    }

    @Test
    @DisplayName("reclaims a document stranded in 'pending' (process died before the async task ran)")
    void reclaimsStrandedPendingDocument() {
        String docPid = createDocumentStuckIn("pending", 30);

        MetaContext.clear();
        int recovered = reconcileService.reclaimStuckDocuments();

        assertThat(recovered).isEqualTo(1);
        assertThat(documentRow(docPid).get("status")).isEqualTo("completed");
    }

    @Test
    @DisplayName("leaves a document that is still legitimately processing alone")
    void ignoresRecentlyStartedDocument() {
        String docPid = createDocumentStuckIn("processing", 2);

        MetaContext.clear();
        int recovered = reconcileService.reclaimStuckDocuments();

        assertThat(recovered).isZero();
        assertThat(documentRow(docPid).get("status")).isEqualTo("processing");
        assertThat((Integer) documentRow(docPid).get("process_retry_count")).isZero();
    }

    @Test
    @DisplayName("gives up after MAX_RETRIES and moves the document to the terminal failed state")
    void exhaustsRetriesAndFails() {
        String docPid = createDocumentStuckIn("processing", 30);
        jdbcTemplate.update("UPDATE ab_kb_document SET process_retry_count = ? WHERE pid = ?",
                DocumentReconcileService.MAX_RETRIES, docPid);

        MetaContext.clear();
        int recovered = reconcileService.reclaimStuckDocuments();

        assertThat(recovered).isZero();
        Map<String, Object> row = documentRow(docPid);
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat((String) row.get("error_message")).contains("did not complete after");
        assertThat(row.get("process_completed_at")).isNotNull();
    }

    @Test
    @DisplayName("does not double-insert chunks left behind by the run that died mid-ingest")
    void doesNotDuplicateChunksFromPartialRun() {
        String docPid = createDocumentStuckIn("processing", 30);

        MetaContext.clear();
        reconcileService.reclaimStuckDocuments();
        int afterFirstPass = chunkCount(docPid);
        assertThat(afterFirstPass).isGreaterThan(0);

        // Strand the same document again — it now has chunks from the completed run, exactly like a
        // worker that died after writing some of them.
        jdbcTemplate.update(
                "UPDATE ab_kb_document SET status = 'processing', process_retry_count = 0, "
                + "process_started_at = NOW() - INTERVAL '30 minutes' WHERE pid = ?", docPid);

        reconcileService.reclaimStuckDocuments();

        assertThat(chunkCount(docPid))
                .as("reprocessing must replace the old chunks, not append a second copy")
                .isEqualTo(afterFirstPass);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Create a document and back-date it into the given non-terminal state, as a crash would. */
    private String createDocumentStuckIn(String status, int minutesAgo) {
        CreateKnowledgeBaseRequest req = new CreateKnowledgeBaseRequest();
        req.setName("Reconcile KB " + System.nanoTime());
        KnowledgeBaseDTO kb = kbService.createKnowledgeBase(
                getTestTenant().getId(), getTestUser().getId(), req);

        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "stranded.txt", "txt", "file-pid-stranded", 64L, "file", null);

        jdbcTemplate.update(
                "UPDATE ab_kb_document SET status = ?, "
                + "process_started_at = CASE WHEN ? = 'processing' "
                + "  THEN NOW() - make_interval(mins => ?) ELSE NULL END, "
                + "created_at = NOW() - make_interval(mins => ?) "
                + "WHERE pid = ?",
                status, status, minutesAgo, minutesAgo, doc.getPid());
        return doc.getPid();
    }

    private Map<String, Object> documentRow(String docPid) {
        return jdbcTemplate.queryForMap("SELECT * FROM ab_kb_document WHERE pid = ?", docPid);
    }

    private int chunkCount(String docPid) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_kb_chunk WHERE doc_id = ?", Integer.class, docPid);
        return n == null ? 0 : n;
    }
}
