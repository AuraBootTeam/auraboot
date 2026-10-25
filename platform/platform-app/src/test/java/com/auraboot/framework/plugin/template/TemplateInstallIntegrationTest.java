package com.auraboot.framework.plugin.template;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.service.PluginImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-stack integration test: installs the {@code simple-inventory} OSS template into the
 * test tenant via {@link PluginImportService} and asserts that:
 * <ol>
 *   <li>{@link ImportPreviewResult#isValid()} is {@code true} after parsing</li>
 *   <li>{@link ImportExecuteResult#isSuccess()} is {@code true} after execution</li>
 *   <li>At least one model and one page appear in {@link ImportExecuteResult#getCreatedResources()}</li>
 *   <li>The tenant actually has the {@code tinv_product} model in the database (proves DB write, not just in-memory)</li>
 * </ol>
 *
 * <p>Mirrors the harness from {@link com.auraboot.framework.plugin.PluginImportEnvStampIntegrationTest}:
 * extends {@link BaseIntegrationTest}, which runs under {@code @Transactional @Rollback(true)},
 * so all DB mutations are rolled back after each test method — self-contained, no cross-method state.
 * No mocking of {@link PluginImportService}.</p>
 *
 * <p>Template chosen: {@code simple-inventory} (id {@code com.auraboot.template.simple-inventory},
 * namespace {@code tinv}, models tinv_product / tinv_warehouse / tinv_stock_in / tinv_stock_out).
 * It has no cross-plugin dependencies and a clean config-only structure, making it the safest
 * first choice for an IT environment.</p>
 */
class TemplateInstallIntegrationTest extends BaseIntegrationTest {

    /**
     * Template under test — smallest standalone OSS template, zero cross-plugin deps.
     * The registry id is the directory name (e.g. "simple-inventory"), NOT the pluginId
     * from plugin.json ("com.auraboot.template.simple-inventory").
     * See {@link TemplateRegistry#readTemplate}: id = pluginDir.getFileName().toString().
     */
    private static final String TEMPLATE_ID = "simple-inventory";

    /** One of the models the template declares; used to verify tenant DB write. */
    private static final String EXPECTED_MODEL_CODE = "tinv_product";

    @Autowired
    private TemplateRegistry templateRegistry;

    @Autowired
    private PluginImportService importService;

    @Autowired
    private MetaModelService metaModelService;

    @Test
    void installSimpleInventoryTemplate_createsModelsAndPagesInTenant() {
        // ── Step 1: Locate the real template directory on disk ──────────────────────────
        List<TemplateRegistry.TemplateDef> allTemplates = templateRegistry.listAll();
        TemplateRegistry.TemplateDef templateDef = allTemplates.stream()
                .filter(t -> TEMPLATE_ID.equals(t.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Template '" + TEMPLATE_ID + "' not found in registry. Available: "
                        + allTemplates.stream().map(TemplateRegistry.TemplateDef::id).toList()));

        String absolutePath = templateDef.absolutePath();
        assertThat(absolutePath)
                .as("Template absolutePath must be non-blank")
                .isNotBlank();

        // ── Step 2: Parse the directory (preview) ───────────────────────────────────────
        ImportPreviewResult preview = importService.parseDirectory(absolutePath);

        assertThat(preview.isValid())
                .as("preview.isValid() must be true — parse errors: " + preview.getErrors())
                .isTrue();
        assertThat(preview.getImportId())
                .as("preview must carry an importId for the execute call")
                .isNotBlank();

        // ── Step 3: Build ImportRequest mirroring TemplateController.install ────────────
        ImportRequest importRequest = ImportRequest.builder()
                .importId(preview.getImportId())
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                .autoPublishModels(true)
                .autoPublishFields(true)
                .autoPublishCommands(true)
                .autoPublishPages(true)
                .build();

        // ── Step 4: Execute the install ──────────────────────────────────────────────────
        ImportExecuteResult result = importService.execute(preview.getImportId(), importRequest);

        assertThat(result.isSuccess())
                .as("ImportExecuteResult.success must be true — error: " + result.getErrorMessage()
                        + " | detail: " + result.getErrorDetail())
                .isTrue();

        // ── Step 5: Verify createdResources contains ≥1 model and ≥1 page ───────────────
        assertThat(result.getCreatedResources())
                .as("createdResources map must be non-null and non-empty")
                .isNotNull()
                .isNotEmpty();

        // MODEL key in the map (ResourceType.MODEL.name())
        assertThat(result.getCreatedResources())
                .as("createdResources must contain 'MODEL' entries")
                .containsKey("MODEL");
        assertThat(result.getCreatedResources().get("MODEL"))
                .as("At least 1 model must have been created")
                .isNotEmpty();

        // PAGE key in the map (ResourceType.PAGE.name())
        assertThat(result.getCreatedResources())
                .as("createdResources must contain 'PAGE' entries")
                .containsKey("PAGE");
        assertThat(result.getCreatedResources().get("PAGE"))
                .as("At least 1 page must have been created")
                .isNotEmpty();

        // ── Step 6: Assert the tenant DB actually has tinv_product ───────────────────────
        // MetaContext is set by BaseIntegrationTest.setupTenantContext() → applyTestMetaContext()
        // so findByCode runs scoped to the test tenant.
        MetaModelDTO model = metaModelService.findByCode(EXPECTED_MODEL_CODE);
        assertThat(model)
                .as("MetaModelService.findByCode('" + EXPECTED_MODEL_CODE + "') must return a non-null"
                        + " model after template install — proves DB write reached the tenant")
                .isNotNull();
        assertThat(model.getCode())
                .isEqualTo(EXPECTED_MODEL_CODE);
    }
}
