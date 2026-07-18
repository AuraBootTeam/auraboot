package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.dto.DecisionTableDmnXmlDTO;
import com.auraboot.framework.decision.dto.DecisionTableDmnXmlRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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

    @Test
    void roundTripPreservesValueLabelsInAuraDmnMetadata() throws Exception {
        String table = """
            { "hitPolicy":"FIRST",
              "inputs":[{
                "id":"wdReqType",
                "label":"请假类型",
                "scope":"record",
                "path":"data.wd_req_type",
                "dataType":"dict",
                "allowedValues":["annual","sick"],
                "valueLabels":{"annual":"年假","sick":"病假"}
              }],
              "outputs":[{
                "id":"route",
                "label":"审批路由",
                "dataType":"string",
                "allowedValues":["manager","hr"],
                "valueLabels":{"manager":"主管","hr":"HR"}
              }],
              "rules":[
                {"ruleId":"annual","priority":10,"when":{"wdReqType":{"operator":"EQ","value":"annual"}},"then":{"route":"manager"}},
                {"ruleId":"sick","priority":20,"when":{"wdReqType":{"operator":"EQ","value":"sick"}},"then":{"route":"hr"}}
              ] }
            """;
        DecisionTableDmnXmlRequest request = new DecisionTableDmnXmlRequest();
        request.setDecisionName("leave_route");
        request.setModel(mapper.readTree(table));

        var result = service.roundTrip(request);

        assertThat(result.getValid()).isTrue();
        assertThat(result.getDmnXml()).contains("xmlns:aura=\"https://auraboot.io/schema/dmn/metadata\"");
        assertThat(result.getDmnXml()).contains("aura:valueLabels holder=\"input\" id=\"wdReqType\"");
        assertThat(result.getDmnXml()).contains("aura:valueLabel value=\"annual\" label=\"年假\"");
        assertThat(result.getDmnXml()).contains("aura:valueLabels holder=\"output\" id=\"route\"");
        assertThat(result.getModel().path("inputs").get(0).path("allowedValues").get(0).asText())
                .isEqualTo("annual");
        assertThat(result.getModel().path("inputs").get(0).path("valueLabels").path("annual").asText())
                .isEqualTo("年假");
        assertThat(result.getModel().path("outputs").get(0).path("allowedValues").get(0).asText())
                .isEqualTo("manager");
        assertThat(result.getModel().path("outputs").get(0).path("valueLabels").path("manager").asText())
                .isEqualTo("主管");
        assertThat(result.getModel().path("rules").get(0).path("when").path("wdReqType").path("feel").asText())
                .isEqualTo("\"annual\"");
    }

    @Test
    void importRejectsDmnXmlWithExternalEntity() throws Exception {
        Path secret = Files.createTempFile("auraboot-dmn-xxe-", ".txt");
        Files.writeString(secret, "XXE_SECRET_DO_NOT_READ");
        String dmnXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE definitions [
              <!ENTITY xxe SYSTEM "%s">
            ]>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                         id="definitions" name="definitions" namespace="https://auraboot/test">
              <decision id="route" name="route">
                <decisionTable hitPolicy="FIRST">
                  <input id="amount"><inputExpression><text>&xxe;</text></inputExpression></input>
                  <output id="route" name="route" typeRef="string"/>
                  <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"manager"</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """.formatted(secret.toUri());
        DecisionTableDmnXmlRequest request = new DecisionTableDmnXmlRequest();
        request.setDmnXml(dmnXml);

        var result = service.importDmn(request);

        assertThat(result.getValid()).isFalse();
        assertThat(result.getModel()).isNull();
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().stream().map(DecisionTableDmnXmlDTO.Issue::getMessage).toList())
                .noneMatch(message -> message != null && message.contains("XXE_SECRET_DO_NOT_READ"));
    }
}
