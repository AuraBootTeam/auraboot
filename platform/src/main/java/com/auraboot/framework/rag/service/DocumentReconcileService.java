package com.auraboot.framework.rag.service;

import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Reclaims knowledge-base documents stranded mid-parse (G2-5). Scheduled via
 * SystemTaskInitializer ({@code sys-rag-document-reconcile}, every 5 min).
 *
 * <p>Document parsing runs on an in-process {@code @Async} executor with no durable queue: if the
 * service is restarted (deploy, OOM, crash) while a document is being parsed, that document is
 * left in {@code processing} — or in {@code pending}, if the process died between the row being
 * inserted and the async task being picked up — and nothing ever moves it again. It stays there
 * forever, with no error surfaced to the user.
 *
 * <p>This is outbox-style reconciliation of an observable stuck state, not a self-heal of missing
 * data (red line §8): a document only qualifies once it has sat in a non-terminal state past
 * {@value #STUCK_AFTER_MINUTES} minutes, each attempt increments {@code process_retry_count}, and
 * after {@value #MAX_RETRIES} attempts it is moved to the terminal {@code failed} state with an
 * explicit error message rather than being retried forever.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentReconcileService {

    /** Attempts before a document is declared permanently failed. */
    static final int MAX_RETRIES = 3;
    /** How long a document may sit in a non-terminal state before it is considered stranded. */
    static final int STUCK_AFTER_MINUTES = 15;
    /** Documents reprocessed per pass — parsing is synchronous here, so keep the batch small. */
    static final int BATCH_LIMIT = 20;

    private final DocumentProcessingService processingService;
    private final RagRetrievalMetrics metrics;
    private final JdbcTemplate jdbcTemplate;

    /** Entry point invoked by the scheduler. @return number of documents successfully reprocessed */
    public int reclaimStuckDocuments() {
        // 'processing' is timed from process_started_at (ab_kb_document has no updated_at column);
        // 'pending' from created_at, which covers a crash before the async task ever started.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, kb_id, tenant_id, doc_name, status, process_retry_count "
                + "FROM ab_kb_document "
                + "WHERE deleted_flag = FALSE AND ("
                + "  (status = 'processing' AND process_started_at < NOW() - make_interval(mins => ?))"
                + "  OR (status = 'pending' AND created_at < NOW() - make_interval(mins => ?))) "
                + "ORDER BY created_at ASC LIMIT ?",
                STUCK_AFTER_MINUTES, STUCK_AFTER_MINUTES, BATCH_LIMIT);
        if (rows.isEmpty()) {
            return 0;
        }

        int recovered = 0;
        for (Map<String, Object> row : rows) {
            String docPid = (String) row.get("pid");
            String kbPid = (String) row.get("kb_id");
            long tenantId = ((Number) row.get("tenant_id")).longValue();
            int attempts = ((Number) row.get("process_retry_count")).intValue() + 1;

            if (attempts > MAX_RETRIES) {
                exhaust(docPid, (String) row.get("doc_name"), attempts - 1);
                continue;
            }

            // Count the attempt before running it: a parse that reliably kills the worker must not
            // be able to retry forever.
            jdbcTemplate.update(
                    "UPDATE ab_kb_document SET process_retry_count = ? WHERE pid = ?", attempts, docPid);

            if (reprocess(kbPid, docPid, tenantId, attempts)) {
                recovered++;
            }
        }

        log.info("Document reconcile pass: {} of {} stranded document(s) reprocessed",
                recovered, rows.size());
        return recovered;
    }

    private boolean reprocess(String kbPid, String docPid, long tenantId, int attempt) {
        // The scheduler thread carries no MetaContext (no HTTP request behind it), and the ingest
        // pipeline resolves tenant-scoped embedding config — without this the parse would fail with
        // "MetaContext not initialized".
        boolean owns = !MetaContext.exists();
        if (owns) {
            MetaContext.setSystemTenantContext(tenantId);
        }
        try {
            processingService.processDocumentNow(kbPid, docPid);
        } finally {
            if (owns) {
                MetaContext.clear();
            }
        }

        // processDocumentNow swallows parse errors and records them on the row, so a normal return
        // proves nothing — read the resulting status back rather than counting the call as a win.
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM ab_kb_document WHERE pid = ?", String.class, docPid);
        if ("completed".equals(status)) {
            metrics.recordDocumentReconcile("recovered", 1);
            log.info("Reclaimed stranded document {} (attempt {}/{})", docPid, attempt, MAX_RETRIES);
            return true;
        }

        metrics.recordDocumentReconcile("failed", 1);
        log.warn("Reprocessing stranded document {} left it in status={} (attempt {}/{})",
                docPid, status, attempt, MAX_RETRIES);
        return false;
    }

    private void exhaust(String docPid, String docName, int attempts) {
        jdbcTemplate.update(
                "UPDATE ab_kb_document SET status = 'failed', error_message = ?, "
                + "process_completed_at = NOW() WHERE pid = ?",
                "Parsing did not complete after " + attempts
                        + " attempts (the worker restarted mid-parse). Re-upload or reprocess the document.",
                docPid);
        metrics.recordDocumentReconcile("exhausted", 1);
        log.warn("Document {} ({}) permanently failed after {} parse attempts", docPid, docName, attempts);
    }
}
