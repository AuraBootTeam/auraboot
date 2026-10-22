package com.auraboot.framework.rag.entity;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.rag.mapper.KbChunkMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the {@link KbChunk#getMetadata()} jsonb persistence fix.
 *
 * <p>{@code ab_kb_chunk.metadata} is a {@code jsonb} column and {@code KbChunkMapper} extends
 * {@code BaseMapper} (MyBatis-Plus auto-insert, no explicit {@code ::jsonb} cast), so the
 * {@code @TableField} String field must declare {@link
 * com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}. Without it,
 * inserting a non-null {@code metadata} threw
 * {@code column "metadata" is of type jsonb but expression is of type character varying}.
 * This test writes a non-null metadata value and reads it back to prove the handler is wired.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("KbChunk metadata jsonb round-trip")
class KbChunkMetadataJsonbIntegrationTest {

    private static final String RUN = Long.toString(Math.abs(System.nanoTime()), 36);

    @Autowired
    private KbChunkMapper kbChunkMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long insertedId;

    @BeforeEach
    void setUp() {
        // Satisfy any tenant-aware mapper interceptor; ab_kb_chunk has no FK so the ids are free.
        MetaContext.setContext(999_000L, 999_001L, "covkbc-pid", "covkbc");
    }

    @AfterEach
    void tearDown() {
        try {
            if (insertedId != null) {
                jdbcTemplate.update("DELETE FROM ab_kb_chunk WHERE id = ?", insertedId);
            }
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("insert + selectById persists non-null metadata as jsonb (no varchar→jsonb error)")
    void metadataRoundTrip() {
        KbChunk chunk = new KbChunk();
        chunk.setPid("covkbc-" + RUN);
        chunk.setTenantId(999_000L);
        chunk.setKbId("covkbc-kb-" + RUN);
        chunk.setDocId("covkbc-doc-" + RUN);
        chunk.setChunkIndex(0);
        chunk.setContent("regression content");
        chunk.setMetadata("{\"source\":\"regression\",\"page\":3}");

        int rows = kbChunkMapper.insert(chunk);
        insertedId = chunk.getId();

        assertEquals(1, rows);
        assertNotNull(insertedId, "id should be generated");

        KbChunk reloaded = kbChunkMapper.selectById(insertedId);
        assertNotNull(reloaded);
        assertNotNull(reloaded.getMetadata(), "metadata must persist");
        // jsonb normalizes whitespace AND reorders keys, so assert semantically (key/value present)
        String normalized = reloaded.getMetadata().replace(" ", "");
        assertTrue(normalized.contains("\"source\":\"regression\""), "metadata: " + normalized);
        assertTrue(normalized.contains("\"page\":3"), "metadata: " + normalized);
    }
}
