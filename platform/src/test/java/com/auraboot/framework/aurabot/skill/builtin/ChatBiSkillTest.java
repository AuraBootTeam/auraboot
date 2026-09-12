package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.service.AggregateQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link ChatBiSkill}: the NL-filled params map to a RAW aggregate request
 * (no semantic model) and the response maps to the ChatBiResultCard {records, columns, chartType}
 * payload. The aggregate execution itself is the already-tested AggregateQueryService backbone
 * (also exercised live by the S5 dashboard chart golden), so this pins the skill's own mapping.
 */
@DisplayName("ChatBiSkill — NL params -> raw aggregate request -> chart card payload")
class ChatBiSkillTest {

    private final ObjectMapper om = new ObjectMapper();
    private AggregateQueryService aggregateQueryService;
    private ChatBiSkill skill;
    private com.auraboot.framework.behavior.service.AnalyticsJourneyService journey;
    private com.auraboot.framework.semantic.service.SemanticCatalogService catalog;
    private com.auraboot.framework.permission.service.UserPermissionService permissions;


    @BeforeEach
    void setup() throws Exception {
        aggregateQueryService = mock(AggregateQueryService.class);
        catalog = mock(com.auraboot.framework.semantic.service.SemanticCatalogService.class);
        permissions = mock(com.auraboot.framework.permission.service.UserPermissionService.class);
        journey = mock(com.auraboot.framework.behavior.service.AnalyticsJourneyService.class);
        org.mockito.Mockito.lenient().when(journey.requested()).thenReturn("analysis-1");
        skill = new ChatBiSkill(aggregateQueryService, om, catalog, permissions, journey);
    }

    private ObjectNode baseParams() {
        ObjectNode params = om.createObjectNode();
        params.put("modelCode", "crm_lead_common");
        params.putArray("dimensions").add("crm_lead_status");
        ObjectNode metric = params.putArray("metrics").addObject();
        metric.put("field", "pid").put("aggregation", "count").put("alias", "cnt");
        return params;
    }

    private SkillResult run(ObjectNode params) {
        return skill.execute(SkillRequest.builder().skillName("chat_bi").params(params).build());
    }

    @Test
    @DisplayName("maps params to a raw aggregate request and the rows to a records/columns/chartType payload")
    void mapsRequestAndResponse() {
        AggregateQueryResponse resp = new AggregateQueryResponse();
        resp.setRows(List.of(
                Map.of("crm_lead_status", "new", "cnt", 26),
                Map.of("crm_lead_status", "qualified", "cnt", 18)));
        when(aggregateQueryService.execute(any())).thenReturn(resp);

        ObjectNode params = baseParams();
        params.put("chartType", "bar");
        // adversarial: the model tries to smuggle a semantic model / named query — must be stripped.


        SkillResult r = run(params);

        ArgumentCaptor<AggregateQueryRequest> cap = ArgumentCaptor.forClass(AggregateQueryRequest.class);
        verify(aggregateQueryService).execute(cap.capture());
        AggregateQueryRequest req = cap.getValue();
        assertThat(req.getType()).isEqualTo("aggregate");
        assertThat(req.getSemanticModelCode()).as("raw query has no semantic model").isNull();
        assertThat(req.getQueryCode()).as("raw query has no named query").isNull();
        assertThat(req.getModelCode()).isEqualTo("crm_lead_common");
        assertThat(req.getDimensions()).containsExactly("crm_lead_status");
        assertThat(req.getMetrics()).hasSize(1);
        assertThat(req.getMetrics().get(0).getField()).isEqualTo("pid");
        assertThat(req.getMetrics().get(0).getAggregation()).isEqualTo("count");
        assertThat(req.getLimit()).as("defaulted").isEqualTo(100);

        assertThat(r.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);
        JsonNode payload = om.valueToTree(r.getPayload());
        assertThat(payload.get("modelCode").asText()).isEqualTo("crm_lead_common");
        assertThat(payload.get("chartType").asText()).isEqualTo("bar");
        assertThat(payload.get("columns").toString()).contains("crm_lead_status").contains("cnt");
        assertThat(payload.get("records")).hasSize(2);
        assertThat(payload.get("records").get(0).get("crm_lead_status").asText()).isEqualTo("new");
        assertThat(payload.get("rowCount").asInt()).isEqualTo(2);
        // aggregate spec carried for the "save as dashboard" bridge (Slice E)
        assertThat(payload.path("dataSource").path("limit").asInt()).isEqualTo(100);
        assertThat(payload.get("dimensions").toString()).contains("crm_lead_status");
        assertThat(payload.get("metrics")).hasSize(1);
        assertThat(payload.get("metrics").get(0).get("field").asText()).isEqualTo("pid");
        assertThat(payload.get("metrics").get(0).get("aggregation").asText()).isEqualTo("count");
    }

    @Test
    void rejectsUnsupportedQueryParameters() {
        ObjectNode params = baseParams();
        params.put("queryCode", "unguarded_query");
        assertThatThrownBy(() -> run(params)).isInstanceOf(SkillSpiException.class);
    }

    @Test
    @DisplayName("no dimension + no chartType defaults to a number card")
    void noDimensionDefaultsToNumber() {
        AggregateQueryResponse resp = new AggregateQueryResponse();
        resp.setRows(List.of(Map.of("cnt", 90)));
        when(aggregateQueryService.execute(any())).thenReturn(resp);

        ObjectNode params = om.createObjectNode();
        params.put("modelCode", "crm_lead_common");
        ObjectNode metric = params.putArray("metrics").addObject();
        metric.put("field", "pid").put("aggregation", "count").put("alias", "cnt");

        SkillResult r = run(params);
        JsonNode payload = om.valueToTree(r.getPayload());
        assertThat(payload.get("chartType").asText()).isEqualTo("number");
    }

    @Test
    @DisplayName("missing modelCode is rejected")
    void missingModelCode() {
        ObjectNode params = baseParams();
        params.remove("modelCode");
        assertThatThrownBy(() -> run(params)).isInstanceOf(SkillSpiException.class);
    }

    @Test
    @DisplayName("missing metrics is rejected")
    void missingMetrics() {
        ObjectNode params = om.createObjectNode();
        params.put("modelCode", "crm_lead_common");
        assertThatThrownBy(() -> run(params)).isInstanceOf(SkillSpiException.class);
    }

    @Test
    @DisplayName("unsupported aggregation is rejected")
    void badAggregation() {
        ObjectNode params = om.createObjectNode();
        params.put("modelCode", "crm_lead_common");
        params.putArray("metrics").addObject().put("field", "crm_lead_score").put("aggregation", "median");
        assertThatThrownBy(() -> run(params)).isInstanceOf(SkillSpiException.class);
    }
    @org.junit.jupiter.api.AfterEach
    void clearContext() { com.auraboot.framework.application.tenant.MetaContext.clear(); }

    private void setupCatalog() {
        com.auraboot.framework.application.tenant.MetaContext.setCurrentTenantId(7L);
        com.auraboot.framework.application.tenant.MetaContext.setCurrentUserId(9L);
        when(permissions.hasPermission(9L,
                com.auraboot.framework.permission.constants.MetaPermission.META_SEMANTIC_USE)).thenReturn(true);
        var result = new com.auraboot.framework.semantic.dto.SemanticMetaResponse();
        var model = new com.auraboot.framework.semantic.dto.SemanticMetaResponse.ModelMeta();
        model.setCode("sales"); model.setModelRef("orders");
        var visible = new com.auraboot.framework.semantic.dto.SemanticMetaResponse.MetricMeta();
        visible.setCode("revenue");
        var restricted = new com.auraboot.framework.semantic.dto.SemanticMetaResponse.MetricMeta();
        restricted.setCode("margin"); restricted.setRequiredPermissions(List.of("finance.margin"));
        model.setMetrics(List.of(visible, restricted));
        result.setModels(List.of(model));
        when(catalog.listCatalog(7L)).thenReturn(result);
    }

    @Test
    void catalogFiltersRestrictedMetricsWithoutModifyingSharedCatalog() {
        setupCatalog();
        JsonNode payload = om.valueToTree(run(om.createObjectNode().put("action", "catalog")).getPayload());
        assertThat(payload.at("/models/0/metrics")).hasSize(1);
        assertThat(payload.at("/models/0/metrics/0/code").asText()).isEqualTo("revenue");
        assertThat(catalog.listCatalog(7L).getModels().get(0).getMetrics()).hasSize(2);
    }

    @Test
    void semanticQueryCarriesGovernedIdentityAndCompleteFiltersIntoSavedDefinition() {
        setupCatalog();
        ObjectNode params = om.createObjectNode().put("semanticModelCode", "sales").put("limit", 5);
        params.putArray("metrics").addObject().put("field", "revenue").put("aggregation", "sum");
        params.putArray("filters").addObject().put("field", "region").put("operator", "eq").put("value", "East");
        params.putArray("orderBy").addObject().put("field", "revenue").put("direction", "desc");
        params.putObject("timeRange").put("field", "created").put("preset", "mtd");
        AggregateQueryResponse response = new AggregateQueryResponse();
        response.setRows(List.of(Map.of("revenue", 120)));
        when(aggregateQueryService.execute(any())).thenReturn(response);
        JsonNode payload = om.valueToTree(run(params).getPayload());
        assertThat(payload.at("/dataSource/semanticModelCode").asText()).isEqualTo("sales");
        assertThat(payload.at("/dataSource/modelCode").asText()).isEqualTo("orders");
        assertThat(payload.at("/dataSource/filters/0/value").asText()).isEqualTo("East");
        assertThat(payload.at("/dataSource/orderBy")).isEqualTo(params.get("orderBy"));
        assertThat(payload.at("/dataSource/timeRange/preset").asText()).isEqualTo("mtd");
        assertThat(payload.at("/dataSource/limit").asInt()).isEqualTo(5);
        assertThat(payload.at("/columns/0").asText()).isEqualTo("revenue");
    }

    @Test
    void restrictedMetricAndMissingCatalogPermissionAreRejectedBeforeQuery() {
        setupCatalog();
        ObjectNode params = om.createObjectNode().put("semanticModelCode", "sales");
        params.putArray("metrics").addObject().put("field", "margin").put("aggregation", "sum");
        assertThatThrownBy(() -> run(params)).isInstanceOf(SkillSpiException.class);
        when(permissions.hasPermission(9L,
                com.auraboot.framework.permission.constants.MetaPermission.META_SEMANTIC_USE)).thenReturn(false);
        assertThatThrownBy(() -> run(om.createObjectNode().put("action", "catalog")))
                .isInstanceOf(SkillSpiException.class);
        org.mockito.Mockito.verifyNoInteractions(aggregateQueryService);
    }

    @Test
    void queryFailureRecordsFailureWithoutSuccess() {
        when(aggregateQueryService.execute(any())).thenThrow(new IllegalStateException("query failed"));
        assertThatThrownBy(() -> skill.execute(SkillRequest.builder().params(baseParams()).build()))
                .isInstanceOf(IllegalStateException.class);
        verify(journey).failed("analysis-1");
        org.mockito.Mockito.verify(journey, org.mockito.Mockito.never()).succeeded(any(), org.mockito.ArgumentMatchers.anyInt());
    }

}
