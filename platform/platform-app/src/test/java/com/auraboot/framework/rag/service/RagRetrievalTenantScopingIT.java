package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retrieval tenant boundary, exercised through the one method that decides it.
 *
 * <p>{@code RagRetrievalService.resolveTargetKbs} is the only place the boundary is enforced: the
 * search SQL filters chunks by {@code kb_id} alone, so a KB pid that reached it unverified would be
 * read across tenants with no error and no warning. This asserts that an explicit request naming a
 * KB the tenant does not own is refused — not narrowed, not answered — and that the refusal reveals
 * no pid.
 *
 * <p>Embedding is mocked to null so the (allowed) request falls through to keyword search over an
 * empty corpus; the assertion is about whether the request is admitted at all, not about ranking.
 */
@DisplayName("RAG retrieval refuses a knowledge base the tenant does not own")
class RagRetrievalTenantScopingIT extends BaseIntegrationTest {

    @Autowired private RagRetrievalService retrievalService;
    @Autowired private JdbcTemplate jdbcTemplate;

    // Null embedding sends an admitted request down the keyword path; over an empty corpus it
    // returns nothing, which is all we need — this test is about admission, not relevance.
    @MockitoBean private EmbeddingService embeddingService;

    private String ownedKbPid;
    private String foreignKbPid;
    private long foreignTenantId;

    private String insertKb(long tenantId, String name) {
        String pid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_knowledge_base (pid, tenant_id, name, status, chunk_count) "
                + "VALUES (?, ?, ?, 'active', 1)",
                pid, tenantId, name);
        return pid;
    }

    private void seed() {
        long ownTenant = testTenant.getId();
        foreignTenantId = ownTenant + 10_000_000L;   // a tenant that is definitely not us
        ownedKbPid = insertKb(ownTenant, "own-kb");
        foreignKbPid = insertKb(foreignTenantId, "foreign-kb");
    }

    @AfterEach
    void cleanup() {
        if (ownedKbPid != null) {
            jdbcTemplate.update("DELETE FROM ab_knowledge_base WHERE pid IN (?, ?)", ownedKbPid, foreignKbPid);
        }
    }

    @Test
    @DisplayName("naming another tenant's knowledge base is forbidden, and the error names no pid")
    void foreignKbIsRefused() {
        seed();
        assertThatThrownBy(() ->
                retrievalService.retrieve(testTenant.getId(), "warranty length", List.of(foreignKbPid), 5, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(foreignKbPid));
    }

    @Test
    @DisplayName("mixing an owned KB with a foreign one refuses the whole request, not just the foreign pid")
    void mixedListIsRefusedWholesale() {
        seed();
        // The silent-narrowing bug would answer out of ownedKbPid and drop foreignKbPid unnoticed.
        assertThatThrownBy(() ->
                retrievalService.retrieve(testTenant.getId(), "warranty length",
                        List.of(ownedKbPid, foreignKbPid), 5, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a knowledge base pid that does not exist is refused too, not treated as empty")
    void unknownKbIsRefused() {
        seed();
        String neverExisted = UniqueIdGenerator.generate();
        assertThatThrownBy(() ->
                retrievalService.retrieve(testTenant.getId(), "warranty length", List.of(neverExisted), 5, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("the tenant's own knowledge base is admitted")
    void ownedKbIsAdmitted() {
        seed();
        // Admission is the boundary under test; over an empty corpus the retrieval simply returns
        // nothing. The point is that it is NOT refused.
        assertThatCode(() ->
                retrievalService.retrieve(testTenant.getId(), "warranty length", List.of(ownedKbPid), 5, null))
                .doesNotThrowAnyException();
    }
}
