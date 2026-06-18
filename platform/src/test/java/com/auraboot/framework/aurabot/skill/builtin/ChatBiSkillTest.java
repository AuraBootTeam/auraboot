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

    @BeforeEach
    void setup() throws Exception {
        aggregateQueryService = mock(AggregateQueryService.class);
        skill = new ChatBiSkill(aggregateQueryService, om);
        skill.init(); // @PostConstruct
    }

    private ObjectNode baseParams() {
        ObjectNode params = om.createObjectNode();
        params.put("modelCode", "crm_lead");
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
        params.put("semanticModelCode", "evil");
        params.put("queryCode", "evil_query");

        SkillResult r = run(params);

        ArgumentCaptor<AggregateQueryRequest> cap = ArgumentCaptor.forClass(AggregateQueryRequest.class);
        verify(aggregateQueryService).execute(cap.capture());
        AggregateQueryRequest req = cap.getValue();
        assertThat(req.getType()).isEqualTo("aggregate");
        assertThat(req.getSemanticModelCode()).as("raw path forced; smuggled semantic model stripped").isNull();
        assertThat(req.getQueryCode()).as("smuggled named query stripped").isNull();
        assertThat(req.getModelCode()).isEqualTo("crm_lead");
        assertThat(req.getDimensions()).containsExactly("crm_lead_status");
        assertThat(req.getMetrics()).hasSize(1);
        assertThat(req.getMetrics().get(0).getField()).isEqualTo("pid");
        assertThat(req.getMetrics().get(0).getAggregation()).isEqualTo("count");
        assertThat(req.getLimit()).as("defaulted").isEqualTo(100);

        assertThat(r.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);
        JsonNode payload = om.valueToTree(r.getPayload());
        assertThat(payload.get("modelCode").asText()).isEqualTo("crm_lead");
        assertThat(payload.get("chartType").asText()).isEqualTo("bar");
        assertThat(payload.get("columns").toString()).contains("crm_lead_status").contains("cnt");
        assertThat(payload.get("records")).hasSize(2);
        assertThat(payload.get("records").get(0).get("crm_lead_status").asText()).isEqualTo("new");
        assertThat(payload.get("rowCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("no dimension + no chartType defaults to a number card")
    void noDimensionDefaultsToNumber() {
        AggregateQueryResponse resp = new AggregateQueryResponse();
        resp.setRows(List.of(Map.of("cnt", 90)));
        when(aggregateQueryService.execute(any())).thenReturn(resp);

        ObjectNode params = om.createObjectNode();
        params.put("modelCode", "crm_lead");
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
        params.put("modelCode", "crm_lead");
        assertThatThrownBy(() -> run(params)).isInstanceOf(SkillSpiException.class);
    }

    @Test
    @DisplayName("unsupported aggregation is rejected")
    void badAggregation() {
        ObjectNode params = om.createObjectNode();
        params.put("modelCode", "crm_lead");
        params.putArray("metrics").addObject().put("field", "crm_lead_score").put("aggregation", "median");
        assertThatThrownBy(() -> run(params)).isInstanceOf(SkillSpiException.class);
    }
}
