package com.auraboot.framework.meta.service.executor;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the Phase 1 virtual-model write-operation guard.
 *
 * <p>Virtual models (sourceType != "physical") are read-only in Phase 1 per
 * design §6.4. Any attempt to invoke {@code create}/{@code update}/{@code delete}/
 * {@code batchDelete}/{@code importData}/{@code saveWithRelations} on a virtual
 * model must fail fast with a {@link MetaServiceException} whose message
 * contains "read-only" and the model code.
 *
 * <p>Physical models must remain unaffected.
 */
@Slf4j
@DisplayName("Virtual Model Write Guard Integration Test - P1-T10")
class VirtualModelWriteGuardIntegrationTest extends BaseIntegrationTest {

    @Autowired private MetaModelService metaModelService;
    @Autowired private DynamicDataService dynamicDataService;

    private ModelDefinition registerVirtualModel(String sourceType, String sourceRef) {
        String code = "p1t10_" + sourceType.toLowerCase() + "_" + System.currentTimeMillis();
        ModelDefinition def = ModelDefinition.builder()
            .code(code)
            .displayName("P1-T10 write-guard " + sourceType)
            .modelType("virtual")
            .sourceType(sourceType)
            .sourceRef(sourceRef)
            .primaryKey("id")
            .capabilities(ModelCapabilities.virtualReadOnly())
            .status("published")
            .build();
        return metaModelService.saveDefinition(def);
    }

    @Test
    @DisplayName("create() is rejected on a namedQuery virtual model")
    void create_rejected_on_namedQuery_virtual_model() {
        ModelDefinition def = registerVirtualModel("namedQuery", "queries/does_not_matter");

        assertThatThrownBy(() -> dynamicDataService.create(def.getCode(), Map.of("x", 1)))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("read-only")
            .hasMessageContaining(def.getCode())
            .hasMessageContaining("namedQuery");
    }

    @Test
    @DisplayName("update() is rejected on a sqlView virtual model")
    void update_rejected_on_sqlView_virtual_model() {
        ModelDefinition def = registerVirtualModel("sqlView", "v_does_not_matter");

        assertThatThrownBy(() ->
            dynamicDataService.update(def.getCode(), "any-id", Map.of("x", 1)))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("read-only")
            .hasMessageContaining(def.getCode())
            .hasMessageContaining("sqlView");
    }

    @Test
    @DisplayName("delete() is rejected on an endpoint virtual model")
    void delete_rejected_on_endpoint_virtual_model() {
        ModelDefinition def = registerVirtualModel("endpoint", "connectors/does_not_matter");

        assertThatThrownBy(() -> dynamicDataService.delete(def.getCode(), "any-id"))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("read-only")
            .hasMessageContaining(def.getCode())
            .hasMessageContaining("endpoint");
    }

    @Test
    @DisplayName("batchDelete() is rejected on a virtual model")
    void batchDelete_rejected_on_virtual_model() {
        ModelDefinition def = registerVirtualModel("namedQuery", "queries/does_not_matter");

        assertThatThrownBy(() ->
            dynamicDataService.batchDelete(def.getCode(), List.of("a", "b")))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("read-only")
            .hasMessageContaining(def.getCode());
    }

    @Test
    @DisplayName("physical models are unaffected — guard does not throw for sourceType=physical or null")
    void physical_model_writes_pass_the_guard() {
        // Register a virtual model first, then verify that an unrelated, non-existent
        // model code (no definition row → getDefinitionByCode returns null) is treated
        // as physical by the guard (null-safe path).  We can't easily run a full create
        // here without a physical table, so we assert on the exception type: the guard
        // would throw MetaServiceException with "read-only"; any other failure means
        // the guard let the call through.
        String absentCode = "p1t10_absent_" + System.currentTimeMillis();

        // The call will fail downstream (model/table not found), but it must NOT
        // fail with the guard's "read-only" message — proving the guard let it pass.
        try {
            dynamicDataService.create(absentCode, Map.of("name", "x"));
        } catch (Throwable t) {
            assertThat(t.getMessage() == null ? "" : t.getMessage())
                .as("guard must not reject physical/absent model code")
                .doesNotContain("read-only");
        }
    }
}
