package com.auraboot.framework.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.auraboot.framework.rag.service.EmbeddingService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Hermetic query-policy checks; real transaction/HNSW behavior belongs to MemoryEmbeddingScaleLiveIT. */
@ExtendWith(MockitoExtension.class)
class AgentMemorySemanticScanPolicyTest {
    @Mock JdbcTemplate jdbc;
    AgentMemoryService service;
    final List<Map<String,Object>> rows = List.of(Map.of("pid", "expected"));
    final EmbeddingService.EmbeddedVector vector = queryVector();

    private static EmbeddingService.EmbeddedVector queryVector() {
        float[] values = new float[1536]; values[0] = 1;
        return new EmbeddingService.EmbeddedVector(values, "a".repeat(64));
    }

    @BeforeEach void prepare() { service = new AgentMemoryService(jdbc); TransactionSynchronizationManager.setActualTransactionActive(true); }
    @AfterEach void clear() { TransactionSynchronizationManager.setActualTransactionActive(false); }
    List<Map<String,Object>> search() { return service.searchScopedSemanticInSpace(42L, "17", "agent", vector, 5); }
    void capable() {
        when(jdbc.queryForObject("SELECT MAX(extversion) FROM pg_extension WHERE extname='vector'", String.class)).thenReturn("0.8.1");
        when(jdbc.queryForObject("SELECT current_setting('hnsw.iterative_scan', true)", String.class)).thenReturn("relaxed_order");
        when(jdbc.queryForObject("SELECT current_setting('hnsw.ef_search', true)", String.class)).thenReturn("40");
    }

    @Test void restoresCallerSettingAfterTheQueryAndCachesTheExtensionCapability() {
        capable(); when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(rows);
        assertThat(search()).isSameAs(rows); assertThat(search()).isSameAs(rows);
        var ordered = inOrder(jdbc);
        ordered.verify(jdbc).queryForObject("SELECT MAX(extversion) FROM pg_extension WHERE extname='vector'", String.class);
        ordered.verify(jdbc).queryForObject("SELECT current_setting('hnsw.iterative_scan', true)", String.class);
        ordered.verify(jdbc).queryForObject("SELECT current_setting('hnsw.ef_search', true)", String.class);
        ordered.verify(jdbc).execute("SET LOCAL hnsw.iterative_scan=strict_order");
        ordered.verify(jdbc).execute("SET LOCAL hnsw.ef_search=1000");
        ordered.verify(jdbc).queryForList(anyString(), any(Object[].class));
        ordered.verify(jdbc).queryForObject("SELECT set_config('hnsw.iterative_scan', ?, true)", String.class, "relaxed_order");
        ordered.verify(jdbc).queryForObject("SELECT set_config('hnsw.ef_search', ?, true)", String.class, "40");
        verify(jdbc, times(1)).queryForObject("SELECT MAX(extversion) FROM pg_extension WHERE extname='vector'", String.class);
    }

    @Test void olderExtensionKeepsTheExistingQueryWithoutUnsupportedSettings() {
        when(jdbc.queryForObject("SELECT MAX(extversion) FROM pg_extension WHERE extname='vector'", String.class)).thenReturn("0.7.4");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(rows);
        assertThat(search()).isSameAs(rows); verify(jdbc, never()).execute(anyString());
        verify(jdbc, never()).queryForObject("SELECT current_setting('hnsw.iterative_scan', true)", String.class);
    }

    @Test void sqlFailurePropagatesWithoutIssuingRestoreSQLInAnAbortedTransaction() {
        capable(); var failure = new DataAccessResourceFailureException("connection failed");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenThrow(failure);
        assertThatThrownBy(this::search).isSameAs(failure);
        verify(jdbc, never()).queryForObject(eq("SELECT set_config('hnsw.iterative_scan', ?, true)"), eq(String.class), anyString());
    }
}
