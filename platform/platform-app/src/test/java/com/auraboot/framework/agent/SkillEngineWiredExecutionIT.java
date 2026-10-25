package com.auraboot.framework.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.auraboot.framework.agent.controller.AgentRuntimeController;
import com.auraboot.framework.agent.dto.SkillResult;
import com.auraboot.framework.agent.service.SkillAutoGenerator;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the {@code POST /api/agent/skills/{code}/execute} endpoint now <b>actually executes</b> a
 * skill through {@link com.auraboot.framework.agent.service.SkillEngine} — it used to call the
 * planner ({@code AgentSkillService.planSkill}, since renamed) which only returned a step plan and
 * never ran anything, leaving the fully-built SkillEngine with zero production callers.
 *
 * <p>Two complementary proofs:
 * <ol>
 *   <li><b>Routing (deterministic, no seeding):</b> a {@code dsl.query} with no model/queryCode/recordPid
 *       returns SkillEngine's executor-exclusive error {@code "requires at least one of"}. The old planner
 *       could not produce this — it returned {@code {success:true, steps:[...]}}. So this error is proof
 *       the endpoint reached the executor's dispatch logic.</li>
 *   <li><b>Real execution (positive):</b> seed a model + one row, then {@code dsl.query} by model returns
 *       {@code SUCCESS} with the real row in {@code data.records} — real data flowed out, not a plan.</li>
 * </ol>
 *
 * Not @Transactional: the positive test creates a physical dynamic table, so it self-cleans in tearDown.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("The execute-skill endpoint runs skills through SkillEngine, not the planner")
class SkillEngineWiredExecutionIT extends BaseIntegrationTest {

    @Autowired private AgentRuntimeController controller;
    @Autowired private SkillAutoGenerator skillAutoGenerator;
    @Autowired private SchemaManagementService schemaManagementService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper fieldBindingMapper;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private DynamicDataService dynamicDataService;

    private Long tenantId;
    private final List<String> createdTables = new ArrayList<>();
    private final List<Model> createdModels = new ArrayList<>();
    private final List<Field> createdFields = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tenantId = getTestTenant().getId();
        MetaContext.setContext(tenantId, getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
        // Ensure the built-in dsl.command / dsl.query skills exist in this tenant.
        skillAutoGenerator.syncSkills(tenantId);
    }

    @AfterEach
    void tearDown() {
        try {
            for (String t : createdTables) {
                try { dynamicDataMapper.alterTable("DROP TABLE IF EXISTS " + t); } catch (Exception ignore) {}
            }
            for (Model m : createdModels) { try { fieldBindingMapper.deleteByModelId(m.getId()); } catch (Exception ignore) {} }
            for (Field f : createdFields) { try { metaFieldMapper.deleteById(f.getId()); } catch (Exception ignore) {} }
            for (Model m : createdModels) { try { metaModelMapper.deleteById(m.getId()); } catch (Exception ignore) {} }
            createdTables.clear(); createdModels.clear(); createdFields.clear();
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("routing: dsl.query without params hits the executor's dispatch error (not a planner plan)")
    void executeEndpoint_runsThroughSkillEngine_notPlanner() {
        ApiResponse<SkillResult> resp = controller.executeSkill("dsl.query", Map.of("input", Map.of()));

        SkillResult result = resp.getData();
        assertThat(result).as("endpoint must return a SkillResult from real execution").isNotNull();
        assertThat(result.getSkillCode()).isEqualTo("dsl.query");
        assertThat(result.getStatus())
                .as("empty dsl.query params must fail inside SkillEngine.executeDslQueryDispatch")
                .isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getErrorMessage())
                .as("this error is exclusive to the executor; the old planner returned a step plan instead")
                .contains("requires at least one of");
    }

    @Test
    @DisplayName("execution: dsl.query by model actually lists a real seeded row")
    void executeEndpoint_actuallyExecutes_returningRealRows() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        String fieldCode = "wname_" + suffix;
        String modelCode = createModel(suffix, fieldCode + ":STRING");

        String rowName = "WIRED-" + suffix;
        Map<String, Object> created = dynamicDataService.create(modelCode, Map.of(fieldCode, rowName));
        assertThat(created).as("seed row must be created").isNotNull();

        ApiResponse<SkillResult> resp =
                controller.executeSkill("dsl.query", Map.of("input", Map.of("model", modelCode)));

        SkillResult result = resp.getData();
        assertThat(result).isNotNull();
        assertThat(result.getStatus())
                .as("dsl.query by model must actually run DynamicDataService.list and succeed")
                .isEqualTo(SkillResult.Status.SUCCESS);
        assertThat(result.getOutputType()).isEqualTo("structured_result");
        assertThat(result.getData()).containsKey("records");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.getData().get("records");
        assertThat(records)
                .as("the endpoint executed the query and returned the row we seeded — real data, not a plan")
                .isNotEmpty();
        assertThat(records)
                .anySatisfy(row -> assertThat(String.valueOf(row.get(fieldCode))).isEqualTo(rowName));
    }

    // ==================== helpers (mirror SkillCmdResolutionIT) ====================

    private String createModel(String suffix, String... fieldDefs) {
        String modelCode = "swe_" + suffix;
        String tableName = "mt_" + modelCode.toLowerCase();
        Model model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(tenantId);
        model.setCode(modelCode);
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus(Status.DRAFT.getCode());
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", "SkillEngine Wired Model " + suffix);
        extMap.put("modelType", "entity");
        ext.setExtension(extMap);
        model.setExtension(ext);
        metaModelMapper.insert(model);
        createdModels.add(model);
        int order = 1;
        for (String fieldDef : fieldDefs) {
            String[] parts = fieldDef.split(":");
            Field field = new Field();
            field.setPid(UniqueIdGenerator.generate());
            field.setTenantId(tenantId);
            field.setCode(parts[0]);
            field.setDataType(parts.length > 1 ? parts[1] : "string");
            field.setVersion(1);
            field.setIsCurrent(true);
            field.setStatus(Status.DRAFT.getCode());
            field.setCreatedAt(Instant.now());
            field.setUpdatedAt(Instant.now());
            field.setDeletedFlag(false);
            FieldFeatureBean feature = new FieldFeatureBean();
            feature.setRequired(false);
            field.setFeature(feature);
            ExtensionBean fieldExt = new ExtensionBean();
            Map<String, Object> fieldExtMap = new HashMap<>();
            fieldExtMap.put("displayName", parts[0]);
            fieldExt.setExtension(fieldExtMap);
            field.setExtension(fieldExt);
            metaFieldMapper.insert(field);
            createdFields.add(field);
            ModelFieldBinding binding = new ModelFieldBinding();
            binding.setTenantId(tenantId);
            binding.setModelId(model.getId());
            binding.setFieldId(field.getId());
            binding.setFieldOrder(order++);
            fieldBindingMapper.insert(binding);
        }
        SchemaOperationResult result = schemaManagementService.createTableByModel(modelCode);
        assertThat(result.isSuccess()).as("table creation for %s", modelCode).isTrue();
        createdTables.add(tableName);
        return modelCode;
    }
}
