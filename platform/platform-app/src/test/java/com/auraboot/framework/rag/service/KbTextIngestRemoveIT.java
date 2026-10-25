package com.auraboot.framework.rag.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.dto.CreateKnowledgeBaseRequest;
import com.auraboot.framework.rag.dto.KnowledgeBaseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves, against a real database, that unpublishing a FAQ removes it from retrieval — not just from
 * a status column.
 *
 * <p>This is the load-bearing half of {@code faq:unpublish}: retrieval reads {@code FROM
 * ab_kb_chunk}, so what decides whether the agent can still recall an answer is whether its chunks
 * exist, not what the candidate's status says. A "pulled" FAQ whose chunks are still in the table is
 * the exact failure the command exists to prevent, and it is invisible from the candidate row.
 *
 * <p>No embedding provider is needed: embedding failure is caught inside the chunk pipeline and the
 * chunks are marked for retry, so the chunk rows — the thing under test — still land.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("KbTextIngestService.remove takes a published FAQ out of retrieval, by deleting its chunks")
class KbTextIngestRemoveIT extends BaseIntegrationTest {

    @Autowired private KbTextIngestService kbTextIngestService;
    @Autowired private KnowledgeBaseService kbService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;
    private String kbPid;

    @BeforeEach
    void createKnowledgeBase() {
        applyTestMetaContext();
        tenantId = testTenant.getId();

        CreateKnowledgeBaseRequest req = new CreateKnowledgeBaseRequest();
        req.setName("FAQ unpublish IT");
        req.setDescription("Scratch KB for asserting unpublish removes chunks");
        KnowledgeBaseDTO kb = kbService.createKnowledgeBase(tenantId, testUser.getId(), req);
        kbPid = kb.getPid();
        assertNotNull(kbPid);
    }

    private int liveChunkCount(String candidatePid) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_kb_chunk c JOIN ab_kb_document d ON c.doc_id = d.pid "
                        + "WHERE d.source_type = 'conversation' AND d.source_entity_id = ? AND d.deleted_flag = false",
                Integer.class, candidatePid);
        return n == null ? 0 : n;
    }

    private int liveDocCount(String candidatePid) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_kb_document "
                        + "WHERE source_type = 'conversation' AND source_entity_id = ? AND deleted_flag = false",
                Integer.class, candidatePid);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("after remove, the FAQ's chunks are gone and a second remove reports nothing to do")
    void removeTakesTheFaqOutOfRetrieval() {
        String candidatePid = "faq-cand-" + System.nanoTime();
        String body = "Q: 保修多久？\n\nA: 自购买日起 37 个月，无需额外购买延保。";

        String docPid = kbTextIngestService.ingestText(
                tenantId, kbPid, "conversation", candidatePid, "保修多久？", body);
        assertNotNull(docPid, "ingest must return a document pid");

        // Published: a live document and at least one chunk retrieval could recall.
        assertEquals(1, liveDocCount(candidatePid), "the published FAQ must have exactly one live document");
        assertTrue(liveChunkCount(candidatePid) > 0,
                "the published FAQ must have chunks — otherwise this test could not tell removal apart from a no-op");

        // Unpublish.
        boolean removed = kbTextIngestService.remove(tenantId, kbPid, "conversation", candidatePid);
        assertTrue(removed, "remove must report it removed the document");

        // The load-bearing assertion: no chunks left for retrieval to find.
        assertEquals(0, liveChunkCount(candidatePid),
                "after unpublish the FAQ's chunks must be gone, or the agent would still recall a 'pulled' answer");
        assertEquals(0, liveDocCount(candidatePid), "the live document must be gone too");

        // Idempotent: unpublishing again is a no-op, not an error.
        assertFalse(kbTextIngestService.remove(tenantId, kbPid, "conversation", candidatePid),
                "a second unpublish has nothing to remove and must say so, not fail");

        jdbcTemplate.update("DELETE FROM ab_kb_document WHERE source_entity_id = ?", candidatePid);
    }
}
