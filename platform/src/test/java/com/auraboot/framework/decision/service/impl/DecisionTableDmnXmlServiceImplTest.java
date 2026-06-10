package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.dto.DecisionTableDmnXmlRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionTableDmnXmlServiceImplTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DecisionTableDmnXmlServiceImpl service = new DecisionTableDmnXmlServiceImpl(mapper);

    @Test
    void exportsKieCompilableDmnDecisionTableAndImportsItBack() throws Exception {
        String table = """
            { "hitPolicy":"FIRST",
              "inputs":[{"id":"amount","label":"Amount","scope":"record","path":"data.amount","dataType":"decimal"}],
              "outputs":[{"id":"route","label":"Route","dataType":"string"}],
              "rules":[
                {"ruleId":"high","priority":10,"when":{"amount":{"operator":"EQ","value":"","feel":"> 10000"}},"then":{"route":"director"}},
                {"ruleId":"normal","priority":20,"when":{"amount":{"operator":"EQ","value":"","feel":"-"}},"then":{"route":"manager"}}] }
            """;
        DecisionTableDmnXmlRequest request = new DecisionTableDmnXmlRequest();
        request.setDecisionId("amount_route");
        request.setDecisionName("amount_route");
        request.setModel(mapper.readTree(table));

        var exported = service.exportDmn(request);

        assertThat(exported.getValid()).isTrue();
        assertThat(exported.getDmnXml()).contains("<decisionTable").contains("hitPolicy=\"FIRST\"");
        assertThat(exported.getDmnXml()).contains("&gt; 10000");

        DecisionTableDmnXmlRequest importRequest = new DecisionTableDmnXmlRequest();
        importRequest.setDmnXml(exported.getDmnXml());
        var imported = service.importDmn(importRequest);

        assertThat(imported.getValid()).isTrue();
        assertThat(imported.getModel().path("inputs").get(0).path("path").asText()).isEqualTo("data.amount");
        assertThat(imported.getModel().path("rules").get(0).path("when").path("amount").path("feel").asText())
                .isEqualTo("> 10000");
        assertThat(imported.getModel().path("rules").get(0).path("then").path("route").asText()).isEqualTo("director");
    }

    @Test
    void roundTripPreservesCollectAggregationAndAllowedValues() throws Exception {
        String table = """
            { "hitPolicy":"COLLECT", "aggregation":"SUM",
              "inputs":[{"id":"tier","label":"Tier","scope":"record","path":"data.tier","dataType":"enum","allowedValues":["GOLD","SILVER"]}],
              "outputs":[{"id":"score","label":"Score","dataType":"decimal"}],
              "rules":[
                {"ruleId":"gold","priority":10,"when":{"tier":{"operator":"EQ","value":"GOLD"}},"then":{"score":100}},
                {"ruleId":"silver","priority":20,"when":{"tier":{"operator":"EQ","value":"SILVER"}},"then":{"score":50}}] }
            """;
        DecisionTableDmnXmlRequest request = new DecisionTableDmnXmlRequest();
        request.setDecisionName("tier_score");
        request.setModel(mapper.readTree(table));

        var result = service.roundTrip(request);

        assertThat(result.getValid()).isTrue();
        assertThat(result.getDmnXml()).contains("hitPolicy=\"COLLECT\"").contains("aggregation=\"SUM\"");
        assertThat(result.getModel().path("hitPolicy").asText()).isEqualTo("COLLECT");
        assertThat(result.getModel().path("aggregation").asText()).isEqualTo("SUM");
        assertThat(result.getModel().path("inputs").get(0).path("allowedValues").get(0).asText()).isEqualTo("GOLD");
        assertThat(result.getModel().path("rules").get(0).path("when").path("tier").path("feel").asText())
                .isEqualTo("\"GOLD\"");
    }
}
