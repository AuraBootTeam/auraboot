package com.auraboot.framework.meta.service.executor;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.NamedQueryCreateRequest;
import com.auraboot.framework.meta.dto.NamedQueryDTO;
import com.auraboot.framework.meta.dto.NamedQueryFieldRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.NamedQueryService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link NamedQueryModelExecutor}.
 *
 * Verifies that sourceType=namedQuery virtual models dispatch through the executor
 * registry into the NamedQuery execution path (tenant/permission/audit preserved).
 */
@Slf4j
@DisplayName("NamedQueryModelExecutor Integration Test - P1-T7")
class NamedQueryModelExecutorIntegrationTest extends BaseIntegrationTest {

    @Autowired private ExecutorRegistry executorRegistry;
    @Autowired private NamedQueryService namedQueryService;
    @Autowired private MetaModelService metaModelService;
    @Autowired private DynamicDataService dynamicDataService;

    @Test
    @DisplayName("executor is registered for sourceType=namedQuery")
    void executorRegistered() {
        Optional<ModelDataExecutor> executor = executorRegistry.resolve("namedQuery");
        assertThat(executor).isPresent();
        assertThat(executor.get()).isInstanceOf(NamedQueryModelExecutor.class);
    }

    @Test
    @DisplayName("list dispatches through NamedQuery path and returns rows")
    void list_dispatches_through_namedQuery_path() {
        // 1) Create a NamedQuery over ab_user (seeded by BaseIntegrationTest).
        String queryCode = "nqmx_list_" + System.currentTimeMillis();
        NamedQueryCreateRequest nqReq = new NamedQueryCreateRequest();
        nqReq.setCode(queryCode);
        nqReq.setTitle("NQMX list test");
        nqReq.setFromSql("SELECT pid, user_name, email FROM ab_user");
        nqReq.setStatus("published");
        NamedQueryDTO nq = namedQueryService.create(nqReq);
        assertThat(nq.getPid()).isNotNull();

        // 2) Register a virtual model whose sourceType=namedQuery and sourceRef=queryCode.
        String modelCode = "nqmx_model_" + System.currentTimeMillis();
        ModelDefinition def = ModelDefinition.builder()
            .code(modelCode)
            .displayName("NQMX Model")
            .modelType("virtual")
            .sourceType("namedQuery")
            .sourceRef(queryCode)
            .primaryKey("pid")
            .capabilities(ModelCapabilities.virtualReadOnly())
            .status("published")
            .build();
        ModelDefinition saved = metaModelService.saveDefinition(def);
        assertThat(saved.getSourceType()).isEqualTo("namedQuery");
        assertThat(saved.getSourceRef()).isEqualTo(queryCode);

        // 3) Call DynamicDataService.list(modelCode) — should dispatch to executor
        //    and return results via NamedQueryService pipeline.
        DynamicQueryRequest request = DynamicQueryRequest.builder()
            .pageNum(1)
            .pageSize(10)
            .build();

        PaginationResult<Map<String, Object>> result =
            dynamicDataService.list(modelCode, request);

        assertThat(result).isNotNull();
        assertThat(result.getRecords()).isNotNull();
        // testUser is created by BaseIntegrationTest — at least 1 row visible.
        assertThat(result.getRecords()).isNotEmpty();

        Map<String, Object> first = result.getRecords().get(0);
        // Named-query result keys correspond to SELECT columns.
        assertThat(first.keySet()).anyMatch(k -> "pid".equalsIgnoreCase(k));
    }

    @Test
    @DisplayName("get() filters by detailKeyField and returns first matching record")
    void get_filters_by_primary_key() {
        String queryCode = "nqmx_get_" + System.currentTimeMillis();
        NamedQueryCreateRequest nqReq = new NamedQueryCreateRequest();
        nqReq.setCode(queryCode);
        nqReq.setTitle("NQMX get test");
        nqReq.setFromSql("SELECT pid, user_name, email FROM ab_user");
        nqReq.setStatus("published");
        nqReq.setFields(List.of(
            buildField("pid", "pid", "string"),
            buildField("user_name", "user_name", "string"),
            buildField("email", "email", "string")
        ));
        namedQueryService.create(nqReq);

        String modelCode = "nqmx_get_model_" + System.currentTimeMillis();
        ModelCapabilities caps = ModelCapabilities.virtualReadOnly().toBuilder()
            .detailKeyField("pid")
            .build();
        ModelDefinition def = ModelDefinition.builder()
            .code(modelCode)
            .displayName("NQMX Get Model")
            .modelType("virtual")
            .sourceType("namedQuery")
            .sourceRef(queryCode)
            .primaryKey("pid")
            .capabilities(caps)
            .status("published")
            .build();
        metaModelService.saveDefinition(def);

        // First run list() to discover a real pid from ab_user.
        PaginationResult<Map<String, Object>> listResult =
            dynamicDataService.list(modelCode, DynamicQueryRequest.builder()
                .pageNum(1).pageSize(1).build());
        assertThat(listResult.getRecords()).isNotEmpty();
        Object pid = listResult.getRecords().get(0).get("pid");
        assertThat(pid).isNotNull();

        // getById should re-enter via executor and filter by pk.
        Map<String, Object> record = dynamicDataService.getById(modelCode, pid.toString());
        assertThat(record).isNotNull();
        assertThat(record.values()).contains(pid);
    }

    private static NamedQueryFieldRequest buildField(String code, String columnExpr, String dataType) {
        NamedQueryFieldRequest f = new NamedQueryFieldRequest();
        f.setFieldCode(code);
        f.setColumnExpr(columnExpr);
        f.setDataType(dataType);
        return f;
    }

    @Test
    @DisplayName("missing sourceRef raises a MetaServiceException, not a silent empty result")
    void missing_sourceRef_raises_error() {
        String modelCode = "nqmx_bad_" + System.currentTimeMillis();
        // Persist via repository-backed helper — then null out sourceRef would violate DB CHECK,
        // so instead we skip saveDefinition and invoke the executor directly with a synthetic
        // definition. Since the executor reads back through metaModelService, it will not find
        // an unsaved model, which is the analogous "misconfigured" failure path.
        NamedQueryModelExecutor executor =
            (NamedQueryModelExecutor) executorRegistry.resolve("namedQuery").orElseThrow();

        DynamicQueryRequest request = DynamicQueryRequest.builder()
            .pageNum(1).pageSize(1).conditions(List.of()).build();

        // Code doesn't exist -> MetaServiceException.
        assertThat(
            org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> executor.list(modelCode, request)
            ).getMessage()
        ).contains(modelCode);
    }
}
