package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.MemoryEmbeddingService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import com.auraboot.framework.integration.TestIdGenerator;

/**
 * Minimal integration for {@link MemoryEmbeddingService}: embedding read
 * path + lazy-compute behaviour when the provider is unconfigured (most
 * likely in CI). The live embedding HTTP call is covered by
 * {@code EmbeddingService}'s own tests.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryEmbeddingService (PR-65)")
class MemoryEmbeddingServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private MemoryEmbeddingService service;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = TestIdGenerator.uniqueTenantId();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    @Test
    @DisplayName("readEmbedding returns null when column is null")
    void readEmbeddingNull() {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, memory_content, importance, scope, created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, 'a', 'fact', 'c', 5, 'user', NOW(), NOW(), FALSE)",
                pid, tenantId);
        double[] v = service.readEmbedding(pid);
        assertThat(v).isNull();
    }

    @Test
    @DisplayName("readEmbedding parses pgvector literal when seeded")
    void readEmbeddingSeeded() {
        String pid = UniqueIdGenerator.generate();
        // Column is vector(1536) — build a literal of exactly that width.
        StringBuilder lit = new StringBuilder("[");
        for (int i = 0; i < 1536; i++) {
            if (i > 0) lit.append(',');
            lit.append(i == 0 ? "0.1" : (i == 1 ? "0.2" : (i == 2 ? "0.3" : "0.0")));
        }
        lit.append(']');
        jdbc.update("INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, memory_content, importance, scope, embedding, created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, 'a', 'fact', 'c', 5, 'user', ?::vector, NOW(), NOW(), FALSE)",
                pid, tenantId, lit.toString());
        double[] v = service.readEmbedding(pid);
        assertThat(v).isNotNull();
        assertThat(v).hasSize(1536);
        assertThat(v[0]).isEqualTo(0.1d, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(v[1]).isEqualTo(0.2d, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(v[2]).isEqualTo(0.3d, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("resolveEmbedding returns null for missing memory")
    void resolveMissing() {
        assertThat(service.resolveEmbedding("does_not_exist_0000000000")).isNull();
    }

    @Test
    @DisplayName("resolveEmbedding without provider configured → null, leaves column null")
    void resolveNoProvider() {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, memory_content, importance, scope, created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, 'a', 'fact', 'c', 5, 'user', NOW(), NOW(), FALSE)",
                pid, tenantId);
        // No CloudConfig seeded in the test tenant → provider resolution returns null → resolveEmbedding must null-out.
        double[] v = service.resolveEmbedding(pid);
        assertThat(v).isNull();
        Integer hasEmbedding = jdbc.queryForObject(
                "SELECT CASE WHEN embedding IS NULL THEN 0 ELSE 1 END FROM ab_agent_memory WHERE pid = ?",
                Integer.class, pid);
        assertThat(hasEmbedding).isEqualTo(0);
    }

    @Test
    @DisplayName("parseVectorLiteral handles edge cases")
    void parseLiteralEdges() {
        assertThat(MemoryEmbeddingService.parseVectorLiteral(null)).isNull();
        assertThat(MemoryEmbeddingService.parseVectorLiteral("")).isNull();
        assertThat(MemoryEmbeddingService.parseVectorLiteral("null")).isNull();
        assertThat(MemoryEmbeddingService.parseVectorLiteral("[]")).isEmpty();
        double[] v = MemoryEmbeddingService.parseVectorLiteral("[1,2,3]");
        assertThat(v).containsExactly(1d, 2d, 3d);
    }
}
