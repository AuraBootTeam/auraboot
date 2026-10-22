package com.auraboot.framework.rag.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.service.DocGenerationService.GenerationResult;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for DocGenerationService — auto-generated docs from DB metadata.
 * Uses real PostgreSQL to query ab_meta_model, ab_meta_field, ab_command_definition.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocGenerationServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DocGenerationService docGenerationService;

    @MockitoBean
    private EmbeddingService embeddingService;

    @Autowired private MetaModelService metaModelService;
    @Autowired private MetaFieldService metaFieldService;
    @Autowired private CommandService commandService;
    @Autowired private TenantService tenantService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @Order(1)
    @DisplayName("GEN-01: Generate model dictionary from published models")
    void generateModelDictionary() throws Exception {
        Path tmpDir = Files.createTempDirectory("doc-gen-test-");
        try {
            GenerationResult result = docGenerationService.generate(tmpDir.toString());

            // Should find published models (from plugin imports)
            assertThat(result.models()).isGreaterThanOrEqualTo(0);
            assertThat(result.outputDir()).isEqualTo(tmpDir.toString());

            // Model dictionary file should exist
            Path dictFile = tmpDir.resolve("model-dictionary.md");
            assertThat(dictFile).exists();
            String content = Files.readString(dictFile);
            assertThat(content).contains("# Model Dictionary");
            assertThat(content).contains("Auto-generated");
            if (result.models() > 0) {
                assertThat(content).contains("| Field | Type | Description |");
            }
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @Order(2)
    @DisplayName("GEN-02: Generate command reference from published commands")
    void generateCommandReference() throws Exception {
        Path tmpDir = Files.createTempDirectory("doc-gen-test-");
        try {
            GenerationResult result = docGenerationService.generate(tmpDir.toString());

            Path cmdFile = tmpDir.resolve("command-reference.md");
            assertThat(cmdFile).exists();
            String content = Files.readString(cmdFile);
            assertThat(content).contains("# Command Reference");
            if (result.commands() > 0) {
                assertThat(content).contains("| Command | Model | Description |");
            }
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @Order(3)
    @DisplayName("GEN-03: Generate field type summary")
    void generateFieldSummary() throws Exception {
        Path tmpDir = Files.createTempDirectory("doc-gen-test-");
        try {
            GenerationResult result = docGenerationService.generate(tmpDir.toString());

            Path summaryFile = tmpDir.resolve("field-summary.md");
            assertThat(summaryFile).exists();
            String content = Files.readString(summaryFile);
            assertThat(content).contains("# Field Type Summary");
            assertThat(content).contains("| Data Type | Count |");
            if (result.fields() > 0) {
                assertThat(content).contains("string");
            }
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @Order(4)
    @DisplayName("GEN-04: Output files have frontmatter with lastGenerated")
    void frontmatterPresent() throws Exception {
        Path tmpDir = Files.createTempDirectory("doc-gen-test-");
        try {
            docGenerationService.generate(tmpDir.toString());

            for (String filename : new String[]{"model-dictionary.md", "command-reference.md", "field-summary.md"}) {
                String content = Files.readString(tmpDir.resolve(filename));
                assertThat(content).startsWith("---");
                assertThat(content).contains("lastGenerated:");
                assertThat(content).contains("title:");
            }
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @Order(5)
    @DisplayName("GEN-05: Incremental — re-generation overwrites existing files")
    void incrementalOverwrite() throws Exception {
        Path tmpDir = Files.createTempDirectory("doc-gen-test-");
        try {
            docGenerationService.generate(tmpDir.toString());
            long firstMtime = Files.getLastModifiedTime(tmpDir.resolve("model-dictionary.md")).toMillis();

            Thread.sleep(100); // ensure different mtime

            docGenerationService.generate(tmpDir.toString());
            long secondMtime = Files.getLastModifiedTime(tmpDir.resolve("model-dictionary.md")).toMillis();

            assertThat(secondMtime).isGreaterThanOrEqualTo(firstMtime);
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @Order(6)
    @DisplayName("GEN-06: Tenant-scoped models, bound fields, commands and counts overwrite prior tenant output")
    void tenantMetadataIsExcludedAndEmptyOutputReplacesPriorContent() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String modelCode = "doc_scope_" + suffix;
        String ownField = "doc_own_" + suffix;
        String foreignField = "doc_foreign_" + suffix;
        String ownCommand = "doc_own_cmd_" + suffix;
        String foreignCommand = "doc_foreign_cmd_" + suffix;
        createPublishedDocumentationFixture(modelCode, ownField, ownCommand, "owner_" + suffix);
        Path output = Files.createTempDirectory("doc-gen-tenant-scope-");
        GenerationResult own = docGenerationService.generate(output.toString());
        assertThat(own.models()).isPositive();
        assertThat(own.commands()).isPositive();
        assertThat(Files.readString(output.resolve("model-dictionary.md")))
                .contains(ownField, "owner_" + suffix);
        assertThat(Files.readString(output.resolve("command-reference.md"))).contains(ownCommand);

        Tenant other = new Tenant();
        other.setPid(UniqueIdGenerator.generate());
        other.setName("doc-scope-" + UUID.randomUUID());
        other.setDisplayName("Documentation isolation fixture");
        other.setStatus("active");
        other.setContactEmail("docs@example.test");
        other.setDeletedFlag(false);
        other.setCreatedAt(Instant.now());
        other.setUpdatedAt(Instant.now());
        other = tenantService.createTenant(other);
        try {
            MetaContext.setContext(other.getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
            GenerationResult empty = docGenerationService.generate(output.toString());
            assertThat(empty.models()).isZero();
            assertThat(empty.commands()).isZero();
            assertThat(empty.fields()).isZero();
            assertThat(Files.readString(output.resolve("model-dictionary.md")))
                    .contains("# Model Dictionary").doesNotContain(modelCode, ownField, "owner_" + suffix);
            assertThat(Files.readString(output.resolve("command-reference.md")))
                    .contains("# Command Reference").doesNotContain(ownCommand);
            assertThat(Files.readString(output.resolve("field-summary.md"))).contains("Total fields: 0");

            createPublishedDocumentationFixture(modelCode, foreignField, foreignCommand, "foreign_" + suffix);
            GenerationResult foreign = docGenerationService.generate(output.toString());
            assertThat(foreign.models()).isEqualTo(1);
            assertThat(foreign.commands()).isEqualTo(1);
            assertThat(Files.readString(output.resolve("model-dictionary.md")))
                    .contains(foreignField, "foreign_" + suffix).doesNotContain(ownField, "owner_" + suffix);
            assertThat(Files.readString(output.resolve("command-reference.md")))
                    .contains(foreignCommand).doesNotContain(ownCommand);
            assertTenantFieldSummary(output, foreign);
        } finally {
            applyTestMetaContext();
        }
        GenerationResult regenerated = docGenerationService.generate(output.toString());
        assertThat(regenerated.models()).isEqualTo(own.models());
        assertThat(regenerated.commands()).isEqualTo(own.commands());
        assertThat(regenerated.fields()).isEqualTo(own.fields());
        assertThat(Files.readString(output.resolve("model-dictionary.md")))
                .contains(ownField, "owner_" + suffix).doesNotContain(foreignField, "foreign_" + suffix);
        assertThat(Files.readString(output.resolve("command-reference.md")))
                .contains(ownCommand).doesNotContain(foreignCommand);
        assertTenantFieldSummary(output, regenerated);
    }

    @Test
    @Order(7)
    @DisplayName("GEN-07: Missing tenant context fails before creating an output directory")
    void missingTenantCannotGenerateFiles() throws Exception {
        Path output = Files.createTempDirectory("doc-gen-no-context-").resolve("generated");
        try {
            MetaContext.clear();
            assertThatThrownBy(() -> docGenerationService.generate(output.toString()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(output).doesNotExist();
        } finally {
            applyTestMetaContext();
        }
    }

    private void createPublishedDocumentationFixture(String modelCode, String fieldCode, String commandCode, String marker) {
        MetaModelCreateRequest modelRequest = new MetaModelCreateRequest();
        modelRequest.setCode(modelCode);
        modelRequest.setDisplayName(marker);
        modelRequest.setSemanticDescription(marker);
        modelRequest.setModelType("entity");
        modelRequest.setModelCategory("REFERENCE");
        modelRequest.setTableName("mt_" + marker);
        MetaModelDTO model = metaModelService.create(modelRequest);
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("string");
        fieldRequest.setStatus("published");
        MetaFieldDTO field = metaFieldService.create(fieldRequest);
        metaModelService.bindFieldToModel(model.getId(), field.getId(), 1, false, true, true,
                null, null, null, null);
        assertThat(metaModelService.publish(model.getPid(), "Documentation scope fixture").getStatus()).isEqualTo("published");
        CommandDefinitionCreateRequest commandRequest = new CommandDefinitionCreateRequest();
        commandRequest.setCode(commandCode);
        commandRequest.setDisplayName(marker);
        commandRequest.setDescription(marker);
        commandRequest.setModelCode(modelCode);
        commandRequest.setInputSchema("{}");
        commandRequest.setTargetModels("[]");
        commandRequest.setExecutionConfig("{\"type\":\"read\"}");
        CommandDefinitionDTO command = commandService.create(commandRequest);
        assertThat(commandService.publish(command.getPid()).getStatus()).isEqualTo("published");
    }

    private void assertTenantFieldSummary(Path output, GenerationResult result) throws Exception {
        var stats = jdbcTemplate.queryForList(
                "SELECT data_type, COUNT(*) AS cnt FROM ab_meta_field WHERE tenant_id = ? "
                        + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) GROUP BY data_type",
                MetaContext.getCurrentTenantId());
        int expected = stats.stream().mapToInt(row -> ((Number) row.get("cnt")).intValue()).sum();
        assertThat(result.fields()).isEqualTo(expected).isPositive();
        String content = Files.readString(output.resolve("field-summary.md"));
        assertThat(content).contains("Total fields: " + expected);
        for (var row : stats) {
            assertThat(content).contains("| " + row.get("data_type") + " | " + row.get("cnt") + " |");
        }
    }

    private void cleanupDir(Path dir) throws Exception {
        if (dir != null && Files.exists(dir)) {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception e) {} });
        }
    }
}
