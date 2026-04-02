package com.auraboot.framework.rag.service;

import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.dto.CreateKnowledgeBaseRequest;
import com.auraboot.framework.rag.dto.KnowledgeBaseDTO;
import com.auraboot.framework.rag.entity.KbChunk;
import com.auraboot.framework.rag.entity.KbDocument;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for DocumentProcessingService — the parse→chunk→embed→store pipeline.
 * <p>
 * Uses AopTestUtils.getTargetObject() to bypass @Async proxy, so processDocument()
 * runs synchronously within the test's @Transactional boundary.
 * Mocks: EmbeddingService (external API), FileService (file storage).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentProcessingServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DocumentProcessingService processingServiceProxy;

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private FileService fileService;

    /** Direct reference bypassing @Async proxy */
    private DocumentProcessingService processingService;

    @BeforeEach
    public void unwrapProxy() {
        super.setupTenantContext();
        processingService = AopTestUtils.getTargetObject(processingServiceProxy);
    }

    // =========================================================================
    // Successful processing
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("PROC-01: Process TXT document — full pipeline")
    void processDocument_txt() throws Exception {
        Path tmpFile = createTempFile("test.txt",
                "First paragraph about AuraBoot platform.\n\n"
                + "Second paragraph about plugin architecture.\n\n"
                + "Third paragraph about RAG knowledge base.");

        setupFileServiceMock(tmpFile.toString());
        setupEmbeddingMock(1536);

        KnowledgeBaseDTO kb = createTestKb("TXT Process");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "test.txt", "txt", "file-pid-txt", 200L, "file", null);

        processingService.processDocument(kb.getPid(), doc.getPid());

        Map<String, Object> docRow = getDocumentRow(doc.getPid());
        assertThat(docRow.get("status")).isEqualTo("completed");
        assertThat(((Number) docRow.get("char_count")).intValue()).isGreaterThan(0);
        assertThat(((Number) docRow.get("chunk_count")).intValue()).isGreaterThan(0);
        assertThat(docRow.get("error_message")).isNull();

        List<KbChunk> chunks = kbService.listChunks(doc.getPid(), 50);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getContent()).isNotBlank();
        assertThat(chunks.get(0).getCharCount()).isGreaterThan(0);

        verify(embeddingService).embedBatch(eq(getTestTenant().getId()), anyList(), eq("openai"));
        Files.deleteIfExists(tmpFile);
    }

    @Test
    @Order(2)
    @DisplayName("PROC-02: Process MD document preserves markdown structure")
    void processDocument_md() throws Exception {
        Path tmpFile = createTempFile("doc.md",
                "# Architecture Overview\n\n"
                + "AuraBoot uses a modular plugin system.\n\n"
                + "## Plugin Types\n\n"
                + "- ConfigOnly: No Java backend\n"
                + "- Full: With PF4J extension points\n\n"
                + "## DSL Engine\n\n"
                + "The DSL engine powers all business pages.");

        setupFileServiceMock(tmpFile.toString());
        setupEmbeddingMock(1536);

        KnowledgeBaseDTO kb = createTestKb("MD Process");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "doc.md", "MD", "file-pid-md", 300L, "file", null);

        processingService.processDocument(kb.getPid(), doc.getPid());

        Map<String, Object> docRow = getDocumentRow(doc.getPid());
        assertThat(docRow.get("status")).isEqualTo("completed");

        List<KbChunk> chunks = kbService.listChunks(doc.getPid(), 50);
        assertThat(chunks).isNotEmpty();

        String allContent = chunks.stream()
                .map(KbChunk::getContent)
                .reduce("", (a, b) -> a + " " + b);
        assertThat(allContent).contains("Architecture Overview");
        assertThat(allContent).contains("Plugin Types");

        Files.deleteIfExists(tmpFile);
    }

    @Test
    @Order(3)
    @DisplayName("PROC-03: Process HTML document strips tags")
    void processDocument_html() throws Exception {
        Path tmpFile = createTempFile("page.html",
                "<html><body><h1>Product Guide</h1>"
                + "<p>AuraBoot supports <strong>6</strong> document formats.</p>"
                + "<ul><li>PDF</li><li>DOCX</li><li>MD</li></ul>"
                + "</body></html>");

        setupFileServiceMock(tmpFile.toString());
        setupEmbeddingMock(1536);

        KnowledgeBaseDTO kb = createTestKb("HTML Process");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "page.html", "html", "file-pid-html", 200L, "file", null);

        processingService.processDocument(kb.getPid(), doc.getPid());

        Map<String, Object> docRow = getDocumentRow(doc.getPid());
        assertThat(docRow.get("status")).isEqualTo("completed");

        List<KbChunk> chunks = kbService.listChunks(doc.getPid(), 50);
        assertThat(chunks).isNotEmpty();

        String content = chunks.get(0).getContent();
        assertThat(content).contains("Product Guide");
        assertThat(content).contains("AuraBoot");
        assertThat(content).doesNotContain("<html>");
        assertThat(content).doesNotContain("<strong>");

        Files.deleteIfExists(tmpFile);
    }

    // =========================================================================
    // Error handling
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("PROC-04: Missing file marks document as FAILED")
    void processDocument_missingFile() {
        FileEntity fakeFile = new FileEntity();
        fakeFile.setLocalPath("/tmp/non-existent-file-" + System.currentTimeMillis() + ".txt");
        when(fileService.findByPid(anyString())).thenReturn(fakeFile);

        KnowledgeBaseDTO kb = createTestKb("Missing File");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "missing.txt", "txt", "file-pid-missing", 100L, "file", null);

        processingService.processDocument(kb.getPid(), doc.getPid());

        Map<String, Object> docRow = getDocumentRow(doc.getPid());
        assertThat(docRow.get("status")).isEqualTo("failed");
        assertThat((String) docRow.get("error_message")).isNotBlank();
    }

    @Test
    @Order(11)
    @DisplayName("PROC-05: Null filePid marks document as FAILED")
    void processDocument_nullFilePid() {
        when(fileService.findByPid(isNull())).thenReturn(null);

        KnowledgeBaseDTO kb = createTestKb("Null File");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "no-file.txt", "txt", null, 0L, "file", null);

        processingService.processDocument(kb.getPid(), doc.getPid());

        Map<String, Object> docRow = getDocumentRow(doc.getPid());
        assertThat(docRow.get("status")).isEqualTo("failed");
    }

    @Test
    @Order(12)
    @DisplayName("PROC-06: Empty file content marks document as FAILED")
    void processDocument_emptyContent() throws Exception {
        Path tmpFile = createTempFile("empty.txt", "");
        setupFileServiceMock(tmpFile.toString());

        KnowledgeBaseDTO kb = createTestKb("Empty Content");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "empty.txt", "txt", "file-pid-empty", 0L, "file", null);

        processingService.processDocument(kb.getPid(), doc.getPid());

        Map<String, Object> docRow = getDocumentRow(doc.getPid());
        assertThat(docRow.get("status")).isEqualTo("failed");
        assertThat((String) docRow.get("error_message")).contains("No text content");

        Files.deleteIfExists(tmpFile);
    }

    @Test
    @Order(13)
    @DisplayName("PROC-07: Embedding failure still stores chunks (without vectors)")
    void processDocument_embeddingFails() throws Exception {
        Path tmpFile = createTempFile("embed-fail.txt",
                "Content that should be chunked.\n\nBut embedding will fail.");

        setupFileServiceMock(tmpFile.toString());
        when(embeddingService.embedBatch(anyLong(), anyList(), anyString()))
                .thenThrow(new RuntimeException("OpenAI API unavailable"));

        KnowledgeBaseDTO kb = createTestKb("Embed Fail");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "embed-fail.txt", "txt", "file-pid-ef", 100L, "file", null);

        processingService.processDocument(kb.getPid(), doc.getPid());

        Map<String, Object> docRow = getDocumentRow(doc.getPid());
        assertThat(docRow.get("status")).isEqualTo("completed");

        List<KbChunk> chunks = kbService.listChunks(doc.getPid(), 50);
        assertThat(chunks).isNotEmpty();

        Files.deleteIfExists(tmpFile);
    }

    @Test
    @Order(14)
    @DisplayName("PROC-08: Non-existent document PID is handled gracefully")
    void processDocument_nonExistentDoc() {
        KnowledgeBaseDTO kb = createTestKb("Ghost Doc");

        assertThatCode(() ->
                processingService.processDocument(kb.getPid(), "non-existent-doc-pid")
        ).doesNotThrowAnyException();
    }

    @Test
    @Order(15)
    @DisplayName("PROC-09: Non-existent KB PID marks document as FAILED")
    void processDocument_nonExistentKb() {
        KnowledgeBaseDTO kb = createTestKb("Real KB for Doc");
        KbDocument doc = kbService.createDocument(
                getTestTenant().getId(), getTestUser().getId(), kb.getPid(),
                "orphan.txt", "txt", "fp-orphan", 10L, "file", null);

        processingService.processDocument("non-existent-kb-pid", doc.getPid());

        Map<String, Object> docRow = getDocumentRow(doc.getPid());
        assertThat(docRow.get("status")).isEqualTo("failed");
        assertThat((String) docRow.get("error_message")).contains("Knowledge base not found");
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

    private Path createTempFile(String name, String content) throws Exception {
        Path tmpDir = Files.createTempDirectory("rag-proc-test-");
        Path tmpFile = tmpDir.resolve(name);
        Files.writeString(tmpFile, content);
        return tmpFile;
    }

    private void setupFileServiceMock(String localPath) {
        FileEntity fileEntity = new FileEntity();
        fileEntity.setLocalPath(localPath);
        when(fileService.findByPid(anyString())).thenReturn(fileEntity);
    }

    private void setupEmbeddingMock(int dimensions) {
        when(embeddingService.embedBatch(anyLong(), anyList(), anyString()))
                .thenAnswer(invocation -> {
                    List<String> texts = invocation.getArgument(1);
                    return texts.stream().map(t -> {
                        float[] emb = new float[dimensions];
                        for (int i = 0; i < dimensions; i++) {
                            emb[i] = (float) Math.random() * 0.1f;
                        }
                        return emb;
                    }).toList();
                });
    }

    private Map<String, Object> getDocumentRow(String docPid) {
        return jdbcTemplate.queryForMap(
                "SELECT status, char_count, chunk_count, error_message FROM ab_kb_document WHERE pid = ?",
                docPid);
    }
}
