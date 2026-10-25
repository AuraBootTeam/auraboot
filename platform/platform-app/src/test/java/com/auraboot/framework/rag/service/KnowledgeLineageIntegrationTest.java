package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.dto.CreateKnowledgeBaseRequest;
import com.auraboot.framework.rag.dto.KnowledgeBaseDTO;
import com.auraboot.framework.rag.entity.KbDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-PostgreSQL state-machine proof for immutable document versions and
 * atomic full-snapshot index releases.
 */
class KnowledgeLineageIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeLineageService lineageService;

    @Autowired
    private KnowledgeIndexRebuildService rebuildService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void updateRollbackAndDeletePreserveExactReleaseSnapshots() {
        KnowledgeBaseDTO kb = createKnowledgeBase();
        KbDocument docA = createDocument(kb.getPid(), "a.md");
        KbDocument docB = createDocument(kb.getPid(), "b.md");

        PublishedVersion a1 = publish(kb.getPid(), docA.getPid(), "A version one");
        PublishedVersion b1 = publish(kb.getPid(), docB.getPid(), "B version one");
        PublishedVersion a2 = publish(kb.getPid(), docA.getPid(), "A version two");

        assertRelease(a2.releasePid(), "A version two", "B version one");
        assertThat(releaseContents(a2.releasePid())).doesNotContain("A version one");

        assertThat(rebuildService.activateExisting(
                getTestTenant().getId(), kb.getPid(), b1.releasePid())).isTrue();
        assertRelease(b1.releasePid(), "A version one", "B version one");
        assertThat(activeRelease(kb.getPid())).isEqualTo(b1.releasePid());

        assertThat(knowledgeBaseService.deleteDocument(
                getTestTenant().getId(), kb.getPid(), docA.getPid())).isTrue();
        String afterDelete = activeRelease(kb.getPid());
        assertThat(releaseContents(afterDelete)).containsExactly("B version one");

        // Deletion publishes a new view but never destroys evidence referenced
        // by a prior run or release.
        assertRelease(a1.releasePid(), "A version one");
        assertRelease(a2.releasePid(), "A version two", "B version one");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_kb_document_version "
                        + "WHERE tenant_id = ? AND kb_pid = ? AND document_pid = ?",
                Integer.class, getTestTenant().getId(), kb.getPid(), docA.getPid()))
                .isEqualTo(2);
    }

    private KnowledgeBaseDTO createKnowledgeBase() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setName("Immutable lineage " + System.nanoTime());
        return knowledgeBaseService.createKnowledgeBase(
                getTestTenant().getId(), getTestUser().getId(), request);
    }

    private KbDocument createDocument(String kbPid, String name) {
        return knowledgeBaseService.createDocument(
                getTestTenant().getId(),
                getTestUser().getId(),
                kbPid,
                name,
                "md",
                null,
                0L,
                "internal_doc",
                name);
    }

    private PublishedVersion publish(String kbPid, String documentPid, String content) {
        KnowledgeLineageService.IngestLineage lineage = lineageService.beginIngest(
                getTestTenant().getId(), kbPid, documentPid);
        jdbc.update(
                "INSERT INTO ab_kb_chunk ("
                        + "pid, tenant_id, kb_id, doc_id, document_version_pid, "
                        + "index_release_pid, chunk_index, content, char_count, token_count, "
                        + "tsv, embedding_status, created_at, updated_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, "
                        + "to_tsvector('simple', ?), 'failed', NOW(), NOW())",
                UniqueIdGenerator.generate(),
                getTestTenant().getId(),
                kbPid,
                documentPid,
                lineage.documentVersionPid(),
                lineage.indexReleasePid(),
                content,
                content.length(),
                Math.max(1, content.length() / 4),
                content);
        lineageService.activateIngest(
                getTestTenant().getId(),
                kbPid,
                documentPid,
                lineage.documentVersionPid(),
                lineage.indexReleasePid());
        return new PublishedVersion(
                lineage.documentVersionPid(), lineage.indexReleasePid());
    }

    private void assertRelease(String releasePid, String... expectedContents) {
        assertThat(releaseContents(releasePid)).containsExactlyInAnyOrder(expectedContents);
    }

    private List<String> releaseContents(String releasePid) {
        return jdbc.queryForList(
                        "SELECT content FROM ab_kb_chunk "
                                + "WHERE tenant_id = ? AND index_release_pid = ? "
                                + "ORDER BY content",
                        getTestTenant().getId(), releasePid)
                .stream()
                .map(row -> (String) row.get("content"))
                .toList();
    }

    private String activeRelease(String kbPid) {
        return jdbc.queryForObject(
                "SELECT active_index_release_pid FROM ab_knowledge_base "
                        + "WHERE tenant_id = ? AND pid = ?",
                String.class, getTestTenant().getId(), kbPid);
    }

    private record PublishedVersion(String versionPid, String releasePid) {
    }
}
