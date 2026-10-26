package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL/pgvector service qualification, with a unique agent namespace per case. */
@Tag("agent-memory-postgres")
class MemoryGovernancePostgresTest {
    private JdbcTemplate jdbc;
    private AgentMemoryService memories;
    private MemoryCompactionService compaction;
    private TransactionTemplate transaction;
    private String agent;
    private static final long TENANT_A = 942001;
    private static final long TENANT_B = 942002;

    @BeforeEach
    void connect() {
        boolean explicitTestTarget = System.getenv("TEST_DATABASE_URL") != null;
        String url = System.getenv(explicitTestTarget ? "TEST_DATABASE_URL" : "DATABASE_URL");
        if (url == null || (!explicitTestTarget && System.getenv("AURA_RUNTIME_NAME") == null)) {
            throw new IllegalStateException("Requires an explicit TEST_DATABASE_URL or a managed owned runtime");
        }
        DriverManagerDataSource source = new DriverManagerDataSource(url,
                System.getenv(explicitTestTarget ? "TEST_DATABASE_USERNAME" : "DATABASE_USERNAME"),
                System.getenv(explicitTestTarget ? "TEST_DATABASE_PASSWORD" : "DATABASE_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        memories = new AgentMemoryService(jdbc);
        compaction = new MemoryCompactionService(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        agent = "review-" + UniqueIdGenerator.generate();
    }

    private String add(long tenant, String scope, String user, String category, String content) {
        return memories.createScopedMemory(tenant, agent, "fact", category, content, content,
                7, false, scope, user);
    }

    private List<String> titles(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> (String) row.get("memory_title")).toList();
    }

    @Test
    void allRecallReadersBindTenantAndUserButKeepExplicitGlobalAndSystemPaths() {
        add(TENANT_A, "user", "alice", "user", "private-a");
        add(TENANT_A, "user", "bob", "user", "private-b");
        add(TENANT_B, "user", "alice", "user", "other-tenant");
        add(TENANT_A, "tenant", null, "agent", "team");
        add(TENANT_B, "global", null, "agent", "global");
        assertThat(titles(memories.searchScoped(TENANT_A, "alice", agent, "", 20)))
                .containsExactlyInAnyOrder("private-a", "team", "global");
        assertThat(titles(memories.loadScopedByImportance(TENANT_A, "alice", agent, 20)))
                .containsExactlyInAnyOrder("private-a", "team", "global");
        assertThat(titles(memories.loadScopedByImportanceL2Only(TENANT_A, "alice", agent, 20)))
                .containsExactlyInAnyOrder("private-a", "team", "global");
        assertThat(titles(memories.loadForPrompt(TENANT_A, "alice", agent, null, 20)))
                .containsExactlyInAnyOrder("private-a", "team", "global");
        assertThat(titles(memories.loadForPrompt(TENANT_A, null, agent, null, 20)))
                .containsExactlyInAnyOrder("team", "global");
        assertThat(titles(memories.loadForPrompt(TENANT_B, "alice", agent, null, 20)))
                .containsExactlyInAnyOrder("other-tenant", "global");
        float[] vector = new float[1536]; vector[0] = 1;
        String literal = "[1," + "0,".repeat(1534) + "0]";
        jdbc.update("UPDATE ab_agent_memory SET embedding=?::vector WHERE memory_agent_id=?", literal, agent);
        assertThat(titles(memories.searchScopedSemantic(TENANT_A, "alice", agent, vector, 20)))
                .containsExactlyInAnyOrder("private-a", "team", "global");
        assertThat(titles(memories.searchScopedSemantic(TENANT_A, null, agent, vector, 20)))
                .containsExactlyInAnyOrder("team", "global");
    }

    @Test
    void expiredAndDeletedRowsNeverEnterRecallAndPrivateShareableDoesNotLeak() {
        String expired = add(TENANT_A, "user", "alice", "session", "expired");
        jdbc.update("UPDATE ab_agent_memory SET valid_until=NOW()-INTERVAL '1 hour' WHERE pid=?", expired);
        String deleted = add(TENANT_A, "user", "alice", "session", "deleted");
        memories.deleteMemory(TENANT_A, deleted);
        String live = add(TENANT_A, "user", "alice", "session", "live");
        memories.markShareable(TENANT_A, live);
        assertThat(titles(memories.searchScoped(TENANT_A, "alice", agent, "", 20))).containsExactly("live");
        assertThat(titles(memories.loadL1Capped(TENANT_A, "alice", 100)))
                .contains("live").doesNotContain("expired", "deleted");
        assertThat(titles(memories.loadForPrompt(TENANT_A, "alice", agent, null, 20))).containsExactly("live");
        assertThat(memories.listShareableMemories(TENANT_A, 100))
                .noneMatch(row -> live.equals(row.get("pid")));
    }

    @Test
    void accessReinforcementTouchesOnlyTheSelectedVisibleLiveRow() {
        String selected = add(TENANT_A, "user", "alice", "user", "selected");
        String unselected = add(TENANT_A, "user", "alice", "user", "unselected");
        String other = add(TENANT_A, "user", "bob", "user", "other");
        memories.trackAccess(TENANT_A, "alice", selected);
        memories.trackAccess(TENANT_A, "alice", other);
        memories.trackAccess(TENANT_B, "alice", selected);
        assertThat(jdbc.queryForObject("SELECT access_count FROM ab_agent_memory WHERE pid=?", Integer.class, selected)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT access_count FROM ab_agent_memory WHERE pid IN (?,?)", Integer.class, unselected, other))
                .containsOnly(0);
    }

    @Test
    void compactionSeparatesScopesAndPreservesExpiryShadowAndSourceIdentity() {
        for (int i=0; i<11; i++) add(TENANT_A, "global", null, "agent", "public-"+i);
        for (int i=0; i<9; i++) add(TENANT_A, "tenant", null, "agent", "secret-"+i);
        assertThat(transaction.<Integer>execute(status -> compaction.compactBucket(TENANT_A, agent))).isZero();
        for (int i=11; i<20; i++) add(TENANT_A, "global", null, "agent", "public-"+i);
        for (int i=9; i<20; i++) add(TENANT_A, "tenant", null, "agent", "secret-"+i);
        jdbc.update("UPDATE ab_agent_memory SET shadow_mode=TRUE, valid_until=NOW()+INTERVAL '1 day' WHERE memory_agent_id=? AND scope='tenant'", agent);
        assertThat(transaction.<Integer>execute(status -> compaction.compactBucket(TENANT_A, agent))).isEqualTo(40);
        List<Map<String,Object>> outsider = memories.loadScopedByImportance(TENANT_B, "alice", agent, 10);
        assertThat(outsider).hasSize(1);
        assertThat((String)outsider.get(0).get("memory_content")).contains("public-").doesNotContain("secret-");
        Map<String,Object> summary = jdbc.queryForMap("SELECT * FROM ab_agent_memory WHERE memory_agent_id=? AND category='compressed' AND scope='tenant'", agent);
        assertThat(summary.get("shadow_mode")).isEqualTo(true);
        assertThat(summary.get("valid_until")).isNotNull();
        assertThat((String)summary.get("metadata")).contains("source_count=20;source_pids=");
    }
    @Test
    void conversationalWriteRetainsTenantOwnerAndProvenanceInOneRecord() {
        String provenance = "kind=episodic;source_turn_id=turn-42;observed_at=2026-10-26T00:00:00Z;authority=model_response";
        String pid = memories.createScopedMemory(TENANT_A, agent, "turn_summary", "conversation_turn",
                "Past answer", "Customer balance was 100", 4, false, "user", "alice", provenance);
        assertThat(jdbc.queryForMap("SELECT metadata,scope,scope_key,tenant_id FROM ab_agent_memory WHERE pid=?", pid))
                .containsEntry("metadata", provenance).containsEntry("scope", "user").containsEntry("scope_key", "alice")
                .containsEntry("tenant_id", TENANT_A);
        ActiveMemoryService recall = new ActiveMemoryService(memories);
        assertThat(recall.preRecall(TENANT_A, "alice", agent, "balance"))
                .anySatisfy(row -> assertThat((String)row.get("content"))
                        .startsWith("[Historical model response; verify current business data]"));
        assertThat(recall.preRecall(TENANT_B, "alice", agent, "balance")).isEmpty();
    }

}
