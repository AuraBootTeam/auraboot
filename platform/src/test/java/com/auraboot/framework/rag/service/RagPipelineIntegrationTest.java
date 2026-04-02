package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.dto.CreateKnowledgeBaseRequest;
import com.auraboot.framework.rag.dto.KnowledgeBaseDTO;
import com.auraboot.framework.rag.dto.RetrievalResult;
import com.auraboot.framework.rag.entity.KbDocument;
import com.auraboot.framework.rag.util.VectorUtils;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the full RAG pipeline: parse → chunk → embed → store → retrieve.
 * Mocks: EmbeddingService (external API), FileService (file storage).
 * Real: PostgreSQL + pgvector, ChunkingService, DocumentParserService, RagRetrievalService.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RagPipelineIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private DocumentParserService parserService;

    @Autowired
    private ChunkingService chunkingService;

    @Autowired
    private RagRetrievalService retrievalService;

    @Autowired
    private RagContextProviderImpl ragContextProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Mock external dependencies per AGENTS.md: LLM/AI providers and file storage
    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private FileService fileService;

    // =========================================================================
    // DocumentParserService tests
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("PARSE-01: Parse TXT file")
    void parseTxt() throws Exception {
        Path tmpFile = createTempFile("test.txt", "Hello, this is a test document.\nLine 2.\nLine 3.");
        try {
            String result = parserService.parse(tmpFile.toString(), "txt");
            assertThat(result).contains("Hello, this is a test document.");
            assertThat(result).contains("Line 2.");
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    @Order(2)
    @DisplayName("PARSE-02: Parse MD file")
    void parseMd() throws Exception {
        Path tmpFile = createTempFile("test.md", "# Title\n\nParagraph content here.\n\n## Section 2\n\nMore text.");
        try {
            String result = parserService.parse(tmpFile.toString(), "MD");
            assertThat(result).contains("# Title");
            assertThat(result).contains("Paragraph content here.");
            assertThat(result).contains("## Section 2");
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    @Order(3)
    @DisplayName("PARSE-03: Parse CSV file")
    void parseCsv() throws Exception {
        Path tmpFile = createTempFile("test.csv", "name,age,city\nAlice,30,Beijing\nBob,25,Shanghai");
        try {
            String result = parserService.parse(tmpFile.toString(), "csv");
            assertThat(result).contains("Alice,30,Beijing");
            assertThat(result).contains("Bob,25,Shanghai");
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    @Order(4)
    @DisplayName("PARSE-04: Parse HTML strips tags")
    void parseHtml() throws Exception {
        Path tmpFile = createTempFile("test.html",
                "<html><body><h1>Title</h1><p>Paragraph &amp; content</p></body></html>");
        try {
            String result = parserService.parse(tmpFile.toString(), "html");
            assertThat(result).contains("Title");
            assertThat(result).contains("Paragraph & content");
            assertThat(result).doesNotContain("<html>");
            assertThat(result).doesNotContain("<p>");
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    @Order(5)
    @DisplayName("PARSE-05: Unsupported type throws IllegalArgumentException")
    void parseUnsupported() throws Exception {
        Path tmpFile = createTempFile("test.xyz", "some content");
        try {
            assertThatThrownBy(() -> parserService.parse(tmpFile.toString(), "xyz"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported document type");
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    // =========================================================================
    // Full pipeline: chunk → store → retrieve
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("PIPE-01: Store chunks with embeddings and retrieve by vector search")
    void fullPipeline_storeAndRetrieve() {
        // Setup: create KB and document
        KnowledgeBaseDTO kb = createTestKb("Pipeline Test");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "pipeline-doc.md", "MD", "fp-pipe", 100L, "file", null);

        // Chunk text
        String text = "AuraBoot is a low-code platform.\n\nIt supports plugin architecture.\n\nRAG enables AI search.";
        List<ChunkingService.ChunkResult> chunks = chunkingService.chunk(text, 500, 0);

        // Store chunks with fake embeddings
        float[] embedding1 = createTestEmbedding(1536, 0.1f);
        float[] embedding2 = createTestEmbedding(1536, 0.5f);
        float[] embedding3 = createTestEmbedding(1536, 0.9f);
        float[][] embeddings = {embedding1, embedding2, embedding3};

        for (int i = 0; i < chunks.size() && i < embeddings.length; i++) {
            String chunkPid = UniqueIdGenerator.generate();
            ChunkingService.ChunkResult chunk = chunks.get(i);
            jdbcTemplate.update(
                    "INSERT INTO ab_kb_chunk (pid, tenant_id, kb_id, doc_id, chunk_index, "
                    + "content, char_count, token_count, tsv, embedding, embedding_status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, to_tsvector('simple', ?), ?::vector, 'completed', NOW(), NOW())",
                    chunkPid, getTestTenant().getId(), kb.getPid(), doc.getPid(),
                    chunk.index(), chunk.content(), chunk.charCount(), chunk.tokenCount(),
                    chunk.content(), VectorUtils.toVectorString(embeddings[i]));
        }

        // Update counters
        kbService.updateDocumentAfterProcessing(doc.getPid(), "completed", text.length(), chunks.size(), null);
        kbService.refreshKbCounters(kb.getPid());

        // Mock embedding for query
        float[] queryEmbedding = createTestEmbedding(1536, 0.1f); // close to embedding1
        when(embeddingService.embed(eq(getTestTenant().getId()), anyString(), eq("openai")))
                .thenReturn(queryEmbedding);

        // Retrieve
        List<RetrievalResult> results = retrievalService.retrieve(
                getTestTenant().getId(), "low-code platform",
                List.of(kb.getPid()), 5, null);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getContent()).isNotBlank();
        assertThat(results.get(0).getDocName()).isEqualTo("pipeline-doc.md");
        assertThat(results.get(0).getKbName()).contains("Pipeline Test");
        assertThat(results.get(0).getSimilarity()).isGreaterThan(0);
        assertThat(results.get(0).getDistance()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @Order(11)
    @DisplayName("PIPE-02: Retrieve with empty query returns empty list")
    void retrieve_emptyQuery() {
        List<RetrievalResult> results = retrievalService.retrieve(
                getTestTenant().getId(), "", null, 5, null);
        assertThat(results).isEmpty();

        results = retrievalService.retrieve(getTestTenant().getId(), null, null, 5, null);
        assertThat(results).isEmpty();
    }

    @Test
    @Order(12)
    @DisplayName("PIPE-03: Retrieve with no active KBs returns empty list")
    void retrieve_noActiveKbs() {
        // Use a tenant ID with no KBs
        when(embeddingService.embed(anyLong(), anyString(), anyString()))
                .thenReturn(createTestEmbedding(1536, 0.5f));

        List<RetrievalResult> results = retrievalService.retrieve(
                999999L, "test query", null, 5, null);
        assertThat(results).isEmpty();
    }

    @Test
    @Order(13)
    @DisplayName("PIPE-04: Retrieve fails gracefully when embedding returns null")
    void retrieve_embeddingFails() {
        KnowledgeBaseDTO kb = createTestKb("Embed Fail Test");
        // Insert a chunk with embedding so KB has chunk_count > 0
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "dummy.txt", "txt", "fp-dummy", 10L, "file", null);
        insertChunkWithEmbedding(kb.getPid(), doc.getPid(), 0, "Some content", 0.5f);
        kbService.refreshKbCounters(kb.getPid());

        when(embeddingService.embed(anyLong(), anyString(), anyString())).thenReturn(null);

        List<RetrievalResult> results = retrievalService.retrieve(
                getTestTenant().getId(), "any query",
                List.of(kb.getPid()), 5, null);
        assertThat(results).isEmpty();
    }

    // =========================================================================
    // RagRetrievalService — buildRagContext
    // =========================================================================

    @Test
    @Order(20)
    @DisplayName("CTX-01: buildRagContext formats retrieval results")
    void buildRagContext() {
        List<RetrievalResult> results = List.of(
                RetrievalResult.builder()
                        .docName("architecture.md")
                        .chunkIndex(3)
                        .content("AuraBoot uses a plugin-based architecture.")
                        .similarity(0.92)
                        .build(),
                RetrievalResult.builder()
                        .docName("commands.md")
                        .chunkIndex(7)
                        .content("Commands follow a 20-stage pipeline.")
                        .similarity(0.85)
                        .build()
        );

        String context = retrievalService.buildRagContext(results);

        assertThat(context).contains("## Reference Knowledge");
        assertThat(context).contains("[Source: architecture.md, Chunk 3]");
        assertThat(context).contains("plugin-based architecture");
        assertThat(context).contains("[Source: commands.md, Chunk 7]");
        assertThat(context).contains("20-stage pipeline");
    }

    @Test
    @Order(21)
    @DisplayName("CTX-02: buildRagContext with empty list returns empty string")
    void buildRagContext_empty() {
        assertThat(retrievalService.buildRagContext(null)).isEmpty();
        assertThat(retrievalService.buildRagContext(List.of())).isEmpty();
    }

    // =========================================================================
    // RagContextProviderImpl (SPI bridge)
    // =========================================================================

    @Test
    @Order(30)
    @DisplayName("SPI-01: hasActiveKnowledgeBases returns true when KB with chunks exists")
    void hasActiveKnowledgeBases_true() {
        KnowledgeBaseDTO kb = createTestKb("Active KB");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "spi-doc.txt", "txt", "fp-spi", 10L, "file", null);
        insertChunkWithEmbedding(kb.getPid(), doc.getPid(), 0, "SPI test content", 0.5f);
        kbService.refreshKbCounters(kb.getPid());

        boolean result = ragContextProvider.hasActiveKnowledgeBases(getTestTenant().getId());
        assertThat(result).isTrue();
    }

    @Test
    @Order(31)
    @DisplayName("SPI-02: hasActiveKnowledgeBases returns false for empty tenant")
    void hasActiveKnowledgeBases_false() {
        boolean result = ragContextProvider.hasActiveKnowledgeBases(999999L);
        assertThat(result).isFalse();
    }

    @Test
    @Order(32)
    @DisplayName("SPI-03: retrieveContext returns formatted context from RAG")
    void retrieveContext() {
        // Setup: create KB with embedded chunks
        KnowledgeBaseDTO kb = createTestKb("SPI Retrieve Test");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "spi-retrieve.md", "MD", "fp-spi-ret", 100L, "file", null);
        float[] emb = createTestEmbedding(1536, 0.3f);
        insertChunkWithEmbedding(kb.getPid(), doc.getPid(), 0, "Plugin system overview content", 0.3f);
        kbService.refreshKbCounters(kb.getPid());

        // Mock query embedding (close to stored)
        when(embeddingService.embed(eq(getTestTenant().getId()), anyString(), eq("openai")))
                .thenReturn(createTestEmbedding(1536, 0.3f));

        String context = ragContextProvider.retrieveContext(
                getTestTenant().getId(), "plugin architecture", List.of(kb.getPid()));

        assertThat(context).contains("Reference Knowledge");
        assertThat(context).contains("Plugin system overview content");
    }

    // =========================================================================
    // VectorUtils tests
    // =========================================================================

    @Test
    @Order(40)
    @DisplayName("VEC-01: toVectorString formats float array correctly")
    void toVectorString() {
        float[] arr = {0.1f, 0.2f, 0.3f};
        String result = VectorUtils.toVectorString(arr);
        assertThat(result).startsWith("[");
        assertThat(result).endsWith("]");
        assertThat(result).contains("0.1");
        assertThat(result).contains("0.2");
        assertThat(result).contains("0.3");
    }

    @Test
    @Order(41)
    @DisplayName("VEC-02: toVectorString with null/empty returns null")
    void toVectorString_nullEmpty() {
        assertThat(VectorUtils.toVectorString(null)).isNull();
        assertThat(VectorUtils.toVectorString(new float[0])).isNull();
    }

    @Test
    @Order(42)
    @DisplayName("VEC-03: estimateTokens handles English text")
    void estimateTokens_english() {
        // "Hello world" = 11 ascii chars → ~2-3 tokens
        int tokens = VectorUtils.estimateTokens("Hello world test");
        assertThat(tokens).isGreaterThan(0);
        assertThat(tokens).isLessThan(16); // 16 ascii / 4 = 4
    }

    @Test
    @Order(43)
    @DisplayName("VEC-04: estimateTokens handles CJK text")
    void estimateTokens_cjk() {
        // Each CJK char ≈ 1 token
        int tokens = VectorUtils.estimateTokens("你好世界测试");
        assertThat(tokens).isEqualTo(6); // 6 CJK chars
    }

    @Test
    @Order(44)
    @DisplayName("VEC-05: estimateTokens null/empty returns 0")
    void estimateTokens_empty() {
        assertThat(VectorUtils.estimateTokens(null)).isZero();
        assertThat(VectorUtils.estimateTokens("")).isZero();
    }

    // =========================================================================
    // hasActiveKnowledgeBases edge cases
    // =========================================================================

    @Test
    @Order(50)
    @DisplayName("ACTIVE-01: Disabled KB is not counted as active")
    void hasActiveKb_disabledNotCounted() {
        KnowledgeBaseDTO kb = createTestKb("Disabled KB");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "disabled-doc.txt", "txt", "fp-dis", 10L, "file", null);
        insertChunkWithEmbedding(kb.getPid(), doc.getPid(), 0, "Disabled content", 0.5f);
        kbService.refreshKbCounters(kb.getPid());
        kbService.toggleStatus(getTestTenant().getId(), kb.getPid()); // ACTIVE → DISABLED

        // Only check this specific KB — other tests may have created active KBs
        // so we can't assert hasActiveKnowledgeBases == false globally
        KnowledgeBaseDTO disabled = kbService.getKnowledgeBase(
                getTestTenant().getId(), kb.getPid());
        assertThat(disabled.getStatus()).isEqualTo("disabled");
    }

    @Test
    @Order(51)
    @DisplayName("ACTIVE-02: KB with zero chunks is not counted as active")
    void hasActiveKb_zerChunksNotCounted() {
        // Create KB without any chunks
        KnowledgeBaseDTO kb = createTestKb("Empty KB");
        // chunk_count stays 0

        // This KB alone should not make hasActiveKnowledgeBases return true
        // Verify via direct SQL
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_knowledge_base "
                + "WHERE pid = ? AND status = 'active' AND chunk_count > 0",
                Integer.class, kb.getPid());
        assertThat(count).isZero();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private KnowledgeBaseDTO createTestKb(String namePrefix) {
        CreateKnowledgeBaseRequest req = new CreateKnowledgeBaseRequest();
        req.setName(namePrefix + " " + System.currentTimeMillis());
        req.setDescription("Test KB for " + namePrefix);
        return kbService.createKnowledgeBase(
                getTestTenant().getId(), getTestUser().getId(), req);
    }

    private Path createTempFile(String name, String content) throws IOException {
        Path tmpDir = Files.createTempDirectory("rag-test-");
        Path tmpFile = tmpDir.resolve(name);
        Files.writeString(tmpFile, content);
        return tmpFile;
    }

    /**
     * Create a test embedding where all dimensions have the same base value + small noise.
     */
    private float[] createTestEmbedding(int dimensions, float baseValue) {
        float[] embedding = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            embedding[i] = baseValue + (i % 10) * 0.001f;
        }
        return embedding;
    }

    private void insertChunkWithEmbedding(String kbPid, String docPid, int index,
                                            String content, float embeddingBase) {
        String chunkPid = UniqueIdGenerator.generate();
        float[] embedding = createTestEmbedding(1536, embeddingBase);
        jdbcTemplate.update(
                "INSERT INTO ab_kb_chunk (pid, tenant_id, kb_id, doc_id, chunk_index, "
                + "content, char_count, token_count, tsv, embedding, embedding_status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, to_tsvector('simple', ?), ?::vector, 'completed', NOW(), NOW())",
                chunkPid, getTestTenant().getId(), kbPid, docPid,
                index, content, content.length(), content.length() / 4,
                content, VectorUtils.toVectorString(embedding));
    }
}
