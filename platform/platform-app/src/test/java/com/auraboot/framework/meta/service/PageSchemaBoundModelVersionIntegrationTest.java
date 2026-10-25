package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PageSchemaCreateRequest;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.dto.PageSchemaUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage for P1 Task 11: {@code ab_page_schema.extension.boundModelVersion}.
 *
 * <p>Verifies that PageSchemaService snapshots the bound model's {modelCode}@{version}
 * into {@code extension.boundModelVersion} at save time, so P2 drift detection can
 * compare stored vs current at design time.
 */
class PageSchemaBoundModelVersionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PageSchemaService pageSchemaService;

    @Autowired
    private MetaModelService metaModelService;

    @Test
    void create_withModelCode_recordsBoundModelVersion() {
        long suffix = System.currentTimeMillis();
        String modelCode = "p1_t11_bmv_a_" + suffix;
        seedPhysicalModel(modelCode);

        ModelDefinition saved = metaModelService.getDefinitionByCode(modelCode);
        assertThat(saved).isNotNull();
        assertThat(saved.getVersion()).isNotNull();
        int modelVer = saved.getVersion();

        String pageKey = "test_bmv_create_" + suffix;
        PageSchemaCreateRequest request = buildCreateRequest(pageKey, modelCode);
        PageSchemaDTO created = pageSchemaService.create(request);

        PageSchemaDTO reloaded = pageSchemaService.findByPid(created.getPid());
        assertThat(reloaded.getExtension())
                .as("extension.boundModelVersion must be set when modelCode is provided")
                .isNotNull()
                .containsEntry("boundModelVersion", modelCode + "@" + modelVer);
    }

    @Test
    void create_withoutModelCode_doesNotSetBoundModelVersion() {
        long suffix = System.currentTimeMillis();
        String pageKey = "test_bmv_nomodel_" + suffix;

        PageSchemaCreateRequest request = new PageSchemaCreateRequest();
        request.setPageKey(pageKey);
        request.setName("BMV No Model " + suffix);
        request.setTitle("BMV No Model " + suffix);
        request.setKind("list");
        request.setBlocks(List.of());
        // modelCode intentionally omitted

        PageSchemaDTO created = pageSchemaService.create(request);

        PageSchemaDTO reloaded = pageSchemaService.findByPid(created.getPid());
        Map<String, Object> ext = reloaded.getExtension();
        // Either extension is null/empty, or it does not contain the bound-version key.
        if (ext != null) {
            assertThat(ext).doesNotContainKey("boundModelVersion");
        }
    }

    @Test
    void create_withUnknownModelCode_doesNotSetBoundModelVersion() {
        long suffix = System.currentTimeMillis();
        String pageKey = "test_bmv_unknown_" + suffix;
        String unknownCode = "p1_t11_missing_" + suffix;

        PageSchemaCreateRequest request = buildCreateRequest(pageKey, unknownCode);
        PageSchemaDTO created = pageSchemaService.create(request);

        PageSchemaDTO reloaded = pageSchemaService.findByPid(created.getPid());
        Map<String, Object> ext = reloaded.getExtension();
        if (ext != null) {
            assertThat(ext).doesNotContainKey("boundModelVersion");
        }
    }

    @Test
    void update_keepsBoundModelVersionInSync() {
        long suffix = System.currentTimeMillis();
        String modelCode = "p1_t11_bmv_b_" + suffix;
        seedPhysicalModel(modelCode);
        int modelVer = metaModelService.getDefinitionByCode(modelCode).getVersion();

        String pageKey = "test_bmv_update_" + suffix;
        PageSchemaDTO created = pageSchemaService.create(buildCreateRequest(pageKey, modelCode));
        assertThat(pageSchemaService.findByPid(created.getPid()).getExtension())
                .containsEntry("boundModelVersion", modelCode + "@" + modelVer);

        // Updating the page re-resolves and rewrites boundModelVersion from the
        // current model definition (so drift-detection always sees the latest
        // snapshot as of the last save).
        PageSchemaUpdateRequest updateReq = new PageSchemaUpdateRequest();
        updateReq.setDescription("touched " + suffix);
        pageSchemaService.update(created.getPid(), updateReq);

        PageSchemaDTO reloaded = pageSchemaService.findByPid(created.getPid());
        assertThat(reloaded.getExtension())
                .containsEntry("boundModelVersion", modelCode + "@" + modelVer);
    }

    // ---------- helpers ----------

    private void seedPhysicalModel(String modelCode) {
        metaModelService.saveDefinition(ModelDefinition.builder()
                .code(modelCode)
                .displayName("T11 model " + modelCode)
                .sourceType("physical")
                .tableName("mt_" + modelCode)
                .primaryKey("id")
                .capabilities(ModelCapabilities.fullPhysical())
                .build());
    }

    private PageSchemaCreateRequest buildCreateRequest(String pageKey, String modelCode) {
        PageSchemaCreateRequest request = new PageSchemaCreateRequest();
        request.setPageKey(pageKey);
        request.setModelCode(modelCode);
        request.setName("BMV Page " + pageKey);
        request.setTitle("BMV Page " + pageKey);
        request.setKind("list");
        request.setBlocks(List.of());
        return request;
    }
}
