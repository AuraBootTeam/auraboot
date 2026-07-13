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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves, against a real database, that a FAQ distilled from a conversation is stored with
 * {@code source_type = 'conversation'} — and stays that way.
 *
 * <p>This exists because the obvious end-to-end test is worthless here. Ingesting with an
 * unrecognised source type does not fail: {@code KbTextIngestService} silently rewrites it to
 * {@code internal_doc}, the document is stored, the chunks embed, retrieval recalls it, and every
 * "did it work?" assertion goes green. The provenance is simply wrong, forever, and nothing says
 * so. The only test that catches it is one that reads the column back and compares it.
 *
 * <p>Two independent things have to be right for that column to hold 'conversation', and each
 * fails differently:
 * <ul>
 *   <li>the {@code chk_doc_source} CHECK constraint must allow the value — omit it and the INSERT
 *       throws, loudly;</li>
 *   <li>{@code KbTextIngestService.DB_SOURCE_TYPES} must list it — omit it and nothing throws at
 *       all, which is the dangerous half.</li>
 * </ul>
 * The unit test in {@code KbTextIngestServiceTest} guards the second. This guards the first, plus
 * the fact that both agree.
 *
 * <p>No embedding provider is needed: embedding failure is caught inside the chunk pipeline and the
 * chunks are marked for retry, so the document row — the thing under test — still lands.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("ab_kb_document.source_type really is 'conversation' for a FAQ written back from a conversation")
class KbConversationSourceIT extends BaseIntegrationTest {

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
        req.setName("FAQ loop source-type IT");
        req.setDescription("Scratch KB for asserting conversation provenance");
        KnowledgeBaseDTO kb = kbService.createKnowledgeBase(tenantId, testUser.getId(), req);
        kbPid = kb.getPid();
        assertNotNull(kbPid, "knowledge base must have been created");
    }

    @Test
    @DisplayName("stores source_type='conversation' verbatim, and does not duplicate on re-publish")
    void conversationSourceTypeSurvivesToTheDatabase() {
        String candidatePid = "faq-cand-" + System.nanoTime();
        String body = "Q: 退款要多久才能到账？\n\nA: 审核通过后 1 个工作日内提交银行，银行入账 3-5 个工作日。";

        String docPid = kbTextIngestService.ingestText(
                tenantId, kbPid, "conversation", candidatePid, "退款要多久才能到账？", body);
        assertNotNull(docPid, "ingest must return a document pid");

        // The whole point. Not "a document exists" — what its source_type actually says.
        String sourceType = jdbcTemplate.queryForObject(
                "SELECT source_type FROM ab_kb_document WHERE pid = ?", String.class, docPid);
        assertEquals("conversation", sourceType,
                "source_type was silently downgraded — chk_doc_source and DB_SOURCE_TYPES disagree");

        String sourceEntityId = jdbcTemplate.queryForObject(
                "SELECT source_entity_id FROM ab_kb_document WHERE pid = ?", String.class, docPid);
        assertEquals(candidatePid, sourceEntityId,
                "the candidate pid is the provenance link back from the KB document");

        // Re-publishing the same candidate must replace its document, not add a second one:
        // ingestText dedups on (sourceType, sourceId), and the candidate pid is the source id.
        String secondDocPid = kbTextIngestService.ingestText(
                tenantId, kbPid, "conversation", candidatePid, "退款要多久才能到账？", body);
        assertNotNull(secondDocPid);

        // ab_kb_document is soft-deleted (KbDocument is @TableLogic), so the superseded document
        // stays as a tombstone row. What must be true is that exactly one LIVE document remains —
        // a query that ignores deleted_flag sees two rows and looks like a duplication bug.
        List<String> liveDocs = jdbcTemplate.queryForList(
                "SELECT pid FROM ab_kb_document "
                        + "WHERE source_type = 'conversation' AND source_entity_id = ? AND deleted_flag = false",
                String.class, candidatePid);
        assertEquals(1, liveDocs.size(),
                "re-publishing a candidate must replace its live KB document, not add a second one");
        assertEquals(secondDocPid, liveDocs.get(0), "the surviving document must be the newest one");

        // The superseded document's chunks are hard-deleted, which is what actually keeps the
        // stale answer out of retrieval — the tombstone alone would not.
        Integer staleChunks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_kb_chunk WHERE doc_id = ?", Integer.class, docPid);
        assertEquals(0, staleChunks,
                "the superseded document's chunks must be gone, or retrieval would still recall the old answer");

        jdbcTemplate.update("DELETE FROM ab_kb_chunk WHERE doc_id IN (?, ?)", docPid, secondDocPid);
        jdbcTemplate.update("DELETE FROM ab_kb_document WHERE source_entity_id = ?", candidatePid);
    }
}
