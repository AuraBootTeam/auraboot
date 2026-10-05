package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.dto.DrtDefinitionDTO;
import com.auraboot.framework.decision.entity.DrtDefinitionEntity;
import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.mapper.DrtDefinitionMapper;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.exception.ValidationException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Hermetic service/mapper boundary tests; real SQL and browser binding run separately. */
class DrtDefinitionOutputCatalogueTest {
    private final DrtDefinitionMapper definitions = mock(DrtDefinitionMapper.class);
    private final DrtVersionMapper versions = mock(DrtVersionMapper.class);
    private final DrtDefinitionServiceImpl service = new DrtDefinitionServiceImpl(definitions, versions);
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DrtVersionEntity.class);
    }

    @BeforeEach
    void context() {
        MetaContext.setContext(7L, 9L, "user-pid", "tester");
    }

    @AfterEach
    void clear() {
        MetaContext.clear();
    }

    private DrtDefinitionEntity definition(String code) {
        DrtDefinitionEntity row = new DrtDefinitionEntity();
        row.setTenantId(7L);
        row.setDecisionCode(code);
        row.setDecisionName("Decision " + code);
        return row;
    }

    private DrtVersionEntity version(String code, int number, String status, long tenant, String label) {
        DrtVersionEntity row = new DrtVersionEntity();
        row.setTenantId(tenant);
        row.setDecisionCode(code);
        row.setVersion(number);
        row.setStatus(status);
        row.setContentJson(json.valueToTree(Map.of("outputs", List.of(Map.of(
                "id", "deadlineMinutes", "label", label, "dataType", "integer")))));
        return row;
    }

    private void page(DrtDefinitionEntity... rows) {
        Page<DrtDefinitionEntity> page = new Page<>(1, 100);
        page.setRecords(List.of(rows));
        page.setTotal(rows.length);
        doReturn(page).when(definitions).selectPage(any(Page.class), any());
    }

    @Test
    void listLoadsOneTenantScopedBatchAndKeepsBothOutputContracts() {
        page(definition("sla"), definition("routing"));
        DrtVersionEntity sla = version("sla", 2, "PUBLISHED", 7, "截止分钟");
        sla.setOutputSchemaJson(json.valueToTree(Map.of("properties", Map.of(
                "deadlineMinutes", Map.of("title", "截止分钟", "type", "integer")))));
        when(versions.selectList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<DrtVersionEntity> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("tenant_id", "decision_code", "status");
            assertThat(query.getParamNameValuePairs().values())
                    .containsExactlyInAnyOrder(7L, "sla", "routing", "PUBLISHED");
            return List.of(sla, version("routing", 1, "PUBLISHED", 7, "路由"));
        });
        List<DrtDefinitionDTO> rows = service.list(null, 1, 100).getRecords();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getPublishedVersion()).isEqualTo(2);
        assertThat(rows.get(0).getOutputs().get(0).path("label").asText()).isEqualTo("截止分钟");
        assertThat(rows.get(0).getOutputs().get(0).path("id").asText()).isEqualTo("deadlineMinutes");
        assertThat(rows.get(0).getOutputSchemaJson()).isEqualTo(sla.getOutputSchemaJson());
        assertThat(rows.get(1).getOutputs().get(0).path("label").asText()).isEqualTo("路由");
        verify(versions, times(1)).selectList(any());
    }

    @Test
    void readUsesHighestPublishedAndRejectsDraftForeignTenantAndForeignCode() {
        when(definitions.findByTenantAndCode(7L, "sla")).thenReturn(definition("sla"));
        when(versions.selectList(any())).thenReturn(List.of(
                version("sla", 1, "PUBLISHED", 7, "旧值"),
                version("sla", 2, "PUBLISHED", 7, "截止分钟"),
                version("sla", 3, "DRAFT", 7, "草稿"),
                version("sla", 99, "PUBLISHED", 8, "其他租户"),
                version("other", 100, "PUBLISHED", 7, "其他决策")));
        DrtDefinitionDTO row = service.findByCode("sla");
        assertThat(row.getPublishedVersion()).isEqualTo(2);
        assertThat(row.getOutputs().get(0).path("label").asText()).isEqualTo("截止分钟");
    }

    @Test
    void noPublishedVersionDoesNotInventOutputSchema() {
        page(definition("sla"));
        when(versions.selectList(any())).thenReturn(List.of(version("sla", 1, "DRAFT", 7, "草稿")));
        DrtDefinitionDTO row = service.list(null, 1, 100).getRecords().get(0);
        assertThat(row.getPublishedVersion()).isNull();
        assertThat(row.getOutputs()).isNull();
        assertThat(row.getOutputSchemaJson()).isNull();
    }

    @Test
    void emptyPageDoesNotQueryAllTenantVersions() {
        page();
        assertThat(service.list(null, 1, 100).getRecords()).isEmpty();
        verifyNoInteractions(versions);
    }

    @Test
    void missingTenantRejectsBeforeAnyMapperAccess() {
        MetaContext.clear();
        assertThatThrownBy(() -> service.list(null, 1, 100)).isInstanceOf(ValidationException.class);
        verifyNoInteractions(definitions, versions);
    }

    @Test
    void outputCatalogueReadFailureIsNotSwallowedAsAnEmptyCatalogue() {
        page(definition("sla"));
        when(versions.selectList(any())).thenThrow(new IllegalStateException("catalogue unavailable"));
        assertThatThrownBy(() -> service.list(null, 1, 100))
                .isInstanceOf(IllegalStateException.class).hasMessage("catalogue unavailable");
    }
}
