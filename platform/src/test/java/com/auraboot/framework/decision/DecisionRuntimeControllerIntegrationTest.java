package com.auraboot.framework.decision;

import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.mapper.SlaConfigMapper;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.ast.ConditionNode;
import com.auraboot.framework.decision.ast.DataType;
import com.auraboot.framework.decision.ast.Operand;
import com.auraboot.framework.decision.ast.Operator;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.entity.DecisionImpactAckEntity;
import com.auraboot.framework.decision.entity.DecisionUsageRefEntity;
import com.auraboot.framework.decision.entity.DrtLogEntity;
import com.auraboot.framework.decision.mapper.DecisionImpactAckMapper;
import com.auraboot.framework.decision.mapper.DecisionUsageRefMapper;
import com.auraboot.framework.decision.mapper.DrtLogMapper;
import com.auraboot.framework.decision.rule.ConditionSpec;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.DecisionVersionPolicy;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.decision.rule.RuleMappingTarget;
import com.auraboot.framework.decision.rule.RuleValueSource;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyDefinitionEntity;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyDefinitionMapper;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyVersionMapper;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.Dict;
import com.auraboot.framework.meta.entity.DictItem;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.FieldDictBinding;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.entity.payload.FieldRefTargetBean;
import com.auraboot.framework.meta.mapper.DictItemMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaFieldDictBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP golden for the Decision Runtime controller: drives the full lifecycle over MockMvc
 * (validate → create definition → create draft → validate version → publish → evaluate),
 * verifying routing, JSON request/response binding, and the {@code @RequirePermission} guard
 * (perms granted to the test role). Complements the service-layer real-stack IT.
 */
class DecisionRuntimeControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private PermissionMapper permissionMapper;
    @Autowired private RolePermissionMapper rolePermissionMapper;
    @Autowired private UserPermissionService userPermissionService;
    @Autowired private DrtLogMapper logMapper;
    @Autowired private DecisionImpactAckMapper impactAckMapper;
    @Autowired private DecisionUsageRefMapper usageRefMapper;
    @Autowired private DrtPolicyDefinitionMapper policyDefinitionMapper;
    @Autowired private DrtPolicyVersionMapper policyVersionMapper;
    @Autowired private AutomationMapper automationMapper;
    @Autowired private SlaConfigMapper slaConfigMapper;
    @Autowired private BpmProcessDefinitionMapper bpmProcessDefinitionMapper;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper metaModelFieldBindingMapper;
    @Autowired private MetaFieldDictBindingMapper metaFieldDictBindingMapper;
    @Autowired private DictMapper dictMapper;
    @Autowired private DictItemMapper dictItemMapper;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mockMvc;

    private static final String AST = """
        { "type": "compare",
          "left": { "type": "path", "scope": "record", "path": "data.amount", "dataType": "decimal" },
          "operator": "GT",
          "right": { "type": "literal", "value": 10000, "dataType": "decimal" } }
        """;

    @BeforeEach
    void setupAuthAndMockMvc() {
        grant("decision.definition.read", "decision", "definition", "read", "Decision Definition Read");
        grant("decision.definition.manage", "decision", "definition", "manage", "Decision Definition Manage");
        grant("decision.definition.publish", "decision", "definition", "publish", "Decision Definition Publish");
        grant("decision.runtime.evaluate", "decision", "runtime", "evaluate", "Decision Runtime Evaluate");
        userPermissionService.evictUserPermissions(getTestUser().getId());

        Filter contextFilter = (request, response, chain) -> {
            try {
                applyTestMetaContext();
                CustomUserDetails ud = new CustomUserDetails(
                        getTestUser().getUserName(), "test-password",
                        getTestUser().getId(), getTestUser().getPid(),
                        AuthorityUtils.createAuthorityList("role_admin"), true, true, true, true);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities()));
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(contextFilter, "/*").build();
    }

    @Test
    void httpLifecycle_validate_create_publish_evaluate() throws Exception {
        String code = "it_http_" + System.nanoTime();

        // 1. validate a draft AST (no persistence) → valid
        mockMvc.perform(post("/api/decision/validate").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "kind", "SIMPLE_CONDITION",
                                "runtimeAdapter", "AST_EVALUATOR",
                                "contentJson", json.readTree(AST)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.fieldRefs[0]").value("record.data.amount"));

        // 2. create definition
        mockMvc.perform(post("/api/decision/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "decisionCode", code, "decisionName", "HTTP IT",
                                "scopeType", "AUTOMATION", "ownerModule", "decision"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionCode").value(code));

        // 3. create draft version → capture pid
        String draftBody = mockMvc.perform(
                        post("/api/decision/definitions/" + code + "/versions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "kind", "SIMPLE_CONDITION",
                                        "runtimeAdapter", "AST_EVALUATOR",
                                        "contentJson", json.readTree(AST)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pid = json.readTree(draftBody).path("data").path("pid").asText();

        // 4. validate version → VALIDATED
        mockMvc.perform(post("/api/decision/versions/" + pid + "/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true));

        // 5. publish version → PUBLISHED
        mockMvc.perform(post("/api/decision/versions/" + pid + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.version").value(1));

        // 6. evaluate via HTTP → MATCHED for amount > 10000
        mockMvc.perform(post("/api/decision/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "decisionCode", code,
                                "binding", "LATEST",
                                "callerType", "API",
                                "context", Map.of("record", Map.of("data", Map.of("amount", 20000)))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MATCHED"))
                .andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.traceId").isNotEmpty());

        // 7. evaluate not-matched
        mockMvc.perform(post("/api/decision/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "decisionCode", code, "binding", "LATEST", "callerType", "API",
                                "context", Map.of("record", Map.of("data", Map.of("amount", 500)))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_MATCHED"));
    }

    @Test
    void httpEvaluateRejectsCorrelationIdLongerThanAuditColumn() throws Exception {
        String code = "it_http_validation_" + System.nanoTime();
        createDefinition(code);
        createPublishedVersion(code);

        mockMvc.perform(post("/api/decision/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "decisionCode", code,
                                "binding", "LATEST",
                                "callerType", "API",
                                "correlationId", "x".repeat(65),
                                "context", Map.of("record", Map.of("data", Map.of("amount", 20000)))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.context.correlationId").exists());
    }

    @Test
    void httpAnalyzeDecisionTableReturnsFiniteDomainGapsAndConflicts() throws Exception {
        String table = """
            { "hitPolicy":"UNIQUE",
              "inputs":[
                {"id":"tier","label":"Tier","scope":"record","path":"data.tier","dataType":"enum","allowedValues":["GOLD","SILVER"]}],
              "outputs":[{"id":"route","label":"Route","dataType":"string"}],
              "rules":[
                {"ruleId":"gold-a","when":{"tier":{"operator":"EQ","value":"GOLD"}},"then":{"route":"manager"}},
                {"ruleId":"gold-b","when":{"tier":{"operator":"EQ","value":"GOLD"}},"then":{"route":"director"}}] }
            """;

        mockMvc.perform(post("/api/decision/tables/analyze").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("model", json.readTree(table)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.metrics.finiteDomainComplete").value(true))
                .andExpect(jsonPath("$.data.metrics.gapCount").value(1))
                .andExpect(jsonPath("$.data.metrics.conflictCount").value(1))
                .andExpect(jsonPath("$.data.errors[0].code").value("DMN_CONFLICT"))
                .andExpect(jsonPath("$.data.warnings[0].code").value("DMN_GAP"));
    }

    @Test
    void httpAnalyzeDecisionTableReportsUnsupportedFeelAndContinuousDomains() throws Exception {
        String table = """
            { "hitPolicy":"FIRST",
              "inputs":[
                {"id":"dueDate","label":"Due date","scope":"record","path":"data.dueDate","dataType":"string"},
                {"id":"amount","label":"Amount","scope":"record","path":"data.amount","dataType":"decimal"}],
              "outputs":[{"id":"route","label":"Route","dataType":"string"}],
              "rules":[
                {"ruleId":"date-fn","when":{"dueDate":{"operator":"EQ","value":"","feel":"if true then \\"2026-06-10\\" else \\"2026-06-11\\""}},"then":{"route":"director"}}] }
            """;

        String body = mockMvc.perform(post("/api/decision/tables/analyze").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("model", json.readTree(table)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.metrics.finiteDomainComplete").value(false))
                .andReturn().getResponse().getContentAsString();

        JsonNode warnings = json.readTree(body).path("data").path("warnings");
        assertTrue(hasIssueCode(warnings, "DMN_UNSUPPORTED_FEEL"));
        assertTrue(hasIssueCode(warnings, "DMN_CONTINUOUS_DOMAIN"));
    }

    @Test
    void httpDecisionTableFeelBuiltinsAnalyzeAndTestRun() throws Exception {
        String table = """
            { "hitPolicy":"FIRST",
              "inputs":[
                {"id":"submittedOn","label":"Submitted on","scope":"record","path":"data.submittedOn","dataType":"date"},
                {"id":"sla","label":"SLA","scope":"record","path":"data.sla","dataType":"duration"}],
              "outputs":[{"id":"route","label":"Route","dataType":"string"}],
              "rules":[
                {"ruleId":"fast","priority":10,
                 "when":{
                   "submittedOn":{"operator":"EQ","value":"","feel":">= date(2026, 6, 10)"},
                   "sla":{"operator":"EQ","value":"","feel":"<= duration(\\"P2D\\")"}},
                 "then":{"route":"fast"}},
                {"ruleId":"fallback","priority":20,"when":{},"then":{"route":"fallback"}}] }
            """;

        String analyzeBody = mockMvc.perform(post("/api/decision/tables/analyze").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("model", json.readTree(table)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andReturn().getResponse().getContentAsString();
        JsonNode analyzeData = json.readTree(analyzeBody).path("data");
        assertFalse(hasIssueCode(analyzeData.path("warnings"), "DMN_UNSUPPORTED_FEEL"));
        assertFalse(hasIssueCode(analyzeData.path("errors"), "DMN_FEEL_PARSE"));

        mockMvc.perform(post("/api/decision/test-run").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "kind", "DECISION_TABLE",
                                "runtimeAdapter", "PLATFORM_DECISION_TABLE",
                                "contentJson", json.readTree(table),
                                "context", Map.of("record", Map.of("data",
                                        Map.of("submittedOn", "2026-06-11", "sla", "P1D")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MATCHED"))
                .andExpect(jsonPath("$.data.outputs.route").value("fast"))
                .andExpect(jsonPath("$.data.matchedRules[0].ruleId").value("fast"));
    }

    @Test
    void httpDecisionTableTestRunAcceptsEditorModelShape() throws Exception {
        String table = """
            { "hitPolicy":"FIRST",
              "inputs":[
                {"id":"amount","label":"Amount","scope":"record","path":"data.amount","dataType":"decimal"}],
              "outputs":[{"id":"route","label":"Route","dataType":"string"}],
              "rules":[
                {"ruleId":"high","priority":10,"when":{"amount":{"operator":"EQ","value":"","feel":"> 10000"}},"then":{"route":"director"}},
                {"ruleId":"fallback","priority":20,"when":{"amount":{"operator":"EQ","value":"","feel":"-"}},"then":{"route":"manager"}}] }
            """;

        mockMvc.perform(post("/api/decision/test-run").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "kind", "DECISION_TABLE",
                                "runtimeAdapter", "PLATFORM_DECISION_TABLE",
                                "contentJson", json.readTree(table),
                                "context", Map.of("record", Map.of("data", Map.of("amount", 20000)))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MATCHED"))
                .andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.outputs.route").value("director"))
                .andExpect(jsonPath("$.data.matchedRules[0].ruleId").value("high"));
    }

    @Test
    void httpDecisionTableDmnXmlRoundTripExportsAndImportsModel() throws Exception {
        String table = """
            { "hitPolicy":"FIRST",
              "inputs":[
                {"id":"amount","label":"Amount","scope":"record","path":"data.amount","dataType":"decimal"}],
              "outputs":[{"id":"route","label":"Route","dataType":"string"}],
              "rules":[
                {"ruleId":"high","when":{"amount":{"operator":"EQ","value":"","feel":"> 10000"}},"then":{"route":"director"}},
                {"ruleId":"normal","when":{"amount":{"operator":"EQ","value":"","feel":"-"}},"then":{"route":"manager"}}] }
            """;

        mockMvc.perform(post("/api/decision/tables/round-trip").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "decisionName", "amount_route",
                                "model", json.readTree(table)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.dmnXml").isNotEmpty())
                .andExpect(jsonPath("$.data.model.inputs[0].path").value("data.amount"))
                .andExpect(jsonPath("$.data.model.rules[0].when.amount.feel").value("> 10000"))
                .andExpect(jsonPath("$.data.model.rules[0].then.route").value("director"));
    }

    private boolean hasIssueCode(JsonNode issues, String code) {
        if (!issues.isArray()) {
            return false;
        }
        for (JsonNode issue : issues) {
            if (code.equals(issue.path("code").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void httpDashboardSummary_countsRuntimeAndPolicyData() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String code = "it_dash_" + suffix;
        String traceId = "trace-dash-" + suffix;

        mockMvc.perform(post("/api/decision/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "decisionCode", code,
                                "decisionName", "Dashboard IT",
                                "scopeType", "AUTOMATION",
                                "ownerModule", "decision"))))
                .andExpect(status().isOk());

        String draftBody = mockMvc.perform(
                        post("/api/decision/definitions/" + code + "/versions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "kind", "SIMPLE_CONDITION",
                                        "runtimeAdapter", "AST_EVALUATOR",
                                        "contentJson", json.readTree(AST)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pid = json.readTree(draftBody).path("data").path("pid").asText();

        mockMvc.perform(post("/api/decision/versions/" + pid + "/validate"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/decision/versions/" + pid + "/publish"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/decision/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "decisionCode", code,
                                "binding", "LATEST",
                                "callerType", "API",
                                "context", Map.of("record", Map.of("data", Map.of("amount", 20000)))))))
                .andExpect(status().isOk());

        DrtPolicyDefinitionEntity policy = new DrtPolicyDefinitionEntity();
        policy.setPid(UniqueIdGenerator.generate());
        policy.setTenantId(getTestTenant().getId());
        policy.setPolicyCode("it_dash_policy_" + suffix);
        policy.setPolicyName("Dashboard Policy");
        policy.setEventType("FORM_SUBMITTED");
        policy.setTargetType("FORM");
        policy.setTargetKey("dashboard-" + suffix);
        policy.setEnabled(true);
        policy.setCreatedBy(getTestUser().getPid());
        policy.setCreatedAt(Instant.now());
        policy.setUpdatedBy(getTestUser().getPid());
        policy.setUpdatedAt(Instant.now());
        policyDefinitionMapper.insert(policy);

        DrtLogEntity errorLog = new DrtLogEntity();
        errorLog.setPid(UniqueIdGenerator.generate());
        errorLog.setTenantId(getTestTenant().getId());
        errorLog.setTraceId(traceId);
        errorLog.setDecisionCode(code);
        errorLog.setDecisionVersion(1);
        errorLog.setKind("SIMPLE_CONDITION");
        errorLog.setRuntimeAdapter("AST_EVALUATOR");
        errorLog.setCallerType("API");
        errorLog.setMatched(false);
        errorLog.setStatus("ERROR");
        errorLog.setDurationMs(42L);
        errorLog.setErrorMessage("dashboard smoke failure");
        errorLog.setCreatedAt(Instant.now());
        logMapper.insert(errorLog);

        String body = mockMvc.perform(get("/api/decision/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.definitions").isNumber())
                .andExpect(jsonPath("$.data.summary.policies").isNumber())
                .andExpect(jsonPath("$.data.summary.evaluationsToday").isNumber())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = json.readTree(body).path("data");
        assertTrue(data.path("summary").path("definitions").asInt() >= 1);
        assertTrue(data.path("summary").path("policies").asInt() >= 1);
        assertTrue(data.path("summary").path("evaluationsToday").asInt() >= 2);
        assertTrue(data.path("summary").path("matched").asInt() >= 1);
        assertTrue(data.path("summary").path("failed").asInt() >= 1);
        assertTrue(data.path("exceptions").findValuesAsText("traceId").contains(traceId));
    }

    @Test
    void httpModelFields_aggregatesValidatedVersionFieldRefs() throws Exception {
        String code = "it_fields_" + System.nanoTime();

        mockMvc.perform(post("/api/decision/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "decisionCode", code,
                                "decisionName", "Field Catalogue IT",
                                "scopeType", "AUTOMATION",
                                "ownerModule", "decision"))))
                .andExpect(status().isOk());

        String draftBody = mockMvc.perform(
                        post("/api/decision/definitions/" + code + "/versions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "kind", "SIMPLE_CONDITION",
                                        "runtimeAdapter", "AST_EVALUATOR",
                                        "contentJson", json.readTree(AST)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pid = json.readTree(draftBody).path("data").path("pid").asText();

        mockMvc.perform(post("/api/decision/versions/" + pid + "/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldRefs[0]").value("record.data.amount"));

        String body = mockMvc.perform(get("/api/decision/model/fields"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        boolean foundAmount = false;
        for (JsonNode field : json.readTree(body).path("data")) {
            if ("record".equals(field.path("entityCode").asText())
                    && "data.amount".equals(field.path("path").asText())) {
                foundAmount = true;
                assertTrue(field.path("refs").asInt() >= 1);
                boolean referencesDecision = false;
                for (JsonNode decisionCode : field.path("decisionCodes")) {
                    if (code.equals(decisionCode.asText())) {
                        referencesDecision = true;
                    }
                }
                assertTrue(referencesDecision);
            }
        }
        assertTrue(foundAmount);
    }

    @Test
    void httpModelFields_includesPublishedMetaModelFieldsWithoutDecisionRefs() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String modelCode = "it_rule_catalog_model_" + suffix;
        String fieldCode = "it_rule_catalog_priority_" + suffix;
        String modelName = "Rule Catalog Model " + suffix;
        String fieldName = "Rule Catalog Priority " + suffix;

        Model model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(getTestTenant().getId());
        model.setCode(modelCode);
        model.setExtension(extension("displayName", modelName));
        model.setTableName("mt_" + modelCode);
        model.setSourceType("physical");
        model.setVersion(1);
        model.setSemver("1.0.0");
        model.setRowVersion(1);
        model.setIsCurrent(true);
        model.setStatus("published");
        model.setDeletedFlag(false);
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        metaModelMapper.insert(model);

        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(getTestTenant().getId());
        field.setCode(fieldCode);
        field.setDataType("string");
        field.setExtension(extension("displayName", fieldName));
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(false);
        feature.setUnique(false);
        field.setFeature(feature);
        field.setVersion(1);
        field.setSemver("1.0.0");
        field.setRowVersion(1);
        field.setIsCurrent(true);
        field.setStatus("published");
        field.setDeletedFlag(false);
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        metaFieldMapper.insert(field);

        ModelFieldBinding binding = new ModelFieldBinding(getTestTenant().getId(), model.getId(), field.getId(), 0);
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());
        metaModelFieldBindingMapper.insert(binding);

        String body = mockMvc.perform(get("/api/decision/model/fields"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        boolean foundMetaField = false;
        for (JsonNode catalogField : json.readTree(body).path("data")) {
            if ("record".equals(catalogField.path("entityCode").asText())
                    && ("data." + fieldCode).equals(catalogField.path("path").asText())) {
                foundMetaField = true;
                assertTrue(catalogField.path("label").asText().contains(modelName));
                assertTrue(catalogField.path("label").asText().contains(fieldName));
                assertTrue("string".equals(catalogField.path("dataType").asText()));
                assertTrue(catalogField.path("refs").asInt() == 0);
                assertTrue(catalogField.path("decisionCodes").isArray());
                assertTrue(catalogField.path("decisionCodes").isEmpty());
            }
        }
        assertTrue(foundMetaField);
    }

    @Test
    void httpFactCatalog_exposesMetaModelFactsWithDictReferenceAndVirtualSource() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String targetModelCode = "it_supplier_" + suffix;
        String modelCode = "it_invoice_view_" + suffix;
        String dictCode = "it_priority_dict_" + suffix;
        String priorityFieldCode = "priority_" + suffix;
        String supplierFieldCode = "supplier_" + suffix;

        createPublishedModel(targetModelCode, "Supplier " + suffix, "physical", null);
        Model model = createPublishedModel(modelCode, "Invoice View " + suffix, "sqlView", "vw_invoice_" + suffix);

        Dict dict = createPublishedDict(dictCode, "Priority " + suffix);
        createDictItem(dict, "high", "High");
        createDictItem(dict, "low", "Low");

        Field priority = createPublishedField(priorityFieldCode, "dict", "Priority " + suffix);
        bindFieldToModel(model, priority, 0);
        bindFieldToDict(priority, dict);

        FieldRefTargetBean refTarget = new FieldRefTargetBean();
        refTarget.setRefType("entity");
        refTarget.setTargetEntity(targetModelCode);
        refTarget.setValueField("pid");
        refTarget.setDisplayField("name");
        Field supplier = createPublishedField(supplierFieldCode, "reference", "Supplier " + suffix);
        supplier.setRefTarget(refTarget);
        metaFieldMapper.updateById(supplier);
        bindFieldToModel(model, supplier, 1);

        String body = mockMvc.perform(get("/api/decision/facts/catalog")
                        .param("modelCode", modelCode))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = json.readTree(body).path("data");
        JsonNode entity = findEntity(data.path("entities"), modelCode);
        assertTrue(!entity.isMissingNode());
        assertTrue("sqlView".equals(entity.path("sourceType").asText()));
        assertTrue(("vw_invoice_" + suffix).equals(entity.path("sourceRef").asText()));

        JsonNode priorityFact = findFact(entity.path("facts"), "record.data." + priorityFieldCode);
        assertTrue(!priorityFact.isMissingNode());
        assertTrue(dictCode.equals(priorityFact.path("dictCode").asText()));
        assertTrue(hasArrayText(priorityFact.path("operators"), "IN"));
        assertTrue(hasOption(priorityFact.path("allowedValues"), "high", "High"));
        assertTrue(hasOption(priorityFact.path("allowedValues"), "low", "Low"));

        JsonNode supplierFact = findFact(entity.path("facts"), "record.data." + supplierFieldCode);
        assertTrue(!supplierFact.isMissingNode());
        assertTrue("reference".equals(supplierFact.path("dataType").asText()));
        assertTrue(targetModelCode.equals(supplierFact.path("reference").path("targetEntity").asText()));
        assertTrue("pid".equals(supplierFact.path("reference").path("valueField").asText()));
    }

    @Test
    void httpFactCatalog_includesSharedContextFactsForCrossModuleRules() throws Exception {
        String body = mockMvc.perform(get("/api/decision/facts/catalog"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode entities = json.readTree(body).path("data").path("entities");
        assertTrue(!findFact(findEntityByScope(entities, "actor").path("facts"), "actor.userId").isMissingNode());
        assertTrue(!findFact(findEntityByScope(entities, "event").path("facts"), "event.type").isMissingNode());
        assertTrue(!findFact(findEntityByScope(entities, "time").path("facts"), "time.now").isMissingNode());
        assertTrue(!findFact(findEntityByScope(entities, "tenant").path("facts"), "tenant.id").isMissingNode());
    }

    @Test
    void httpActionCatalogReportsRuntimeHandlersAndMarksRemainingUnwiredActions() throws Exception {
        String body = mockMvc.perform(get("/api/decision/actions/catalog"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode actions = json.readTree(body).path("data").path("actions");
        assertAvailableAction(actions, "NOTIFY");
        assertAvailableAction(actions, "START_PROCESS");
        assertAvailableAction(actions, "ADD_COMMENT");
        assertAvailableAction(actions, "UPDATE_RECORD");
        assertAvailableAction(actions, "PATCH_RECORD");
        assertAvailableAction(actions, "WEBHOOK");
        assertAvailableAction(actions, "WRITE_AUDIT");
        assertAvailableAction(actions, "CREATE_TASK");
        assertAvailableAction(actions, "SEND_IM");
        assertAvailableAction(actions, "CC_TASK");
        assertUnavailableAction(actions, "SEND_SMS");
    }

    @Test
    void httpConditionFragments_createEvaluateAndReportImpactRefs() throws Exception {
        String code = "it_fragment_" + System.nanoTime();
        JsonNode conditionSpec = json.readTree("""
            { "root": {
                "type": "group",
                "op": "AND",
                "children": [
                  { "type": "compare",
                    "left": { "type": "path", "scope": "record", "path": "data.amount", "dataType": "decimal" },
                    "operator": "GT",
                    "right": { "type": "literal", "value": 10000, "dataType": "decimal" } },
                  { "type": "not",
                    "child": { "type": "compare",
                      "left": { "type": "path", "scope": "actor", "path": "roles", "dataType": "string" },
                      "operator": "CONTAINS_ELEMENT",
                      "right": { "type": "literal", "value": "internal_auditor", "dataType": "string" } } }
                ] },
              "decisionBindings": [] }
            """);

        String body = mockMvc.perform(post("/api/decision/condition-fragments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "fragmentCode", code,
                                "fragmentName", "High value non-auditor",
                                "description", "Reusable approval condition",
                                "scopeType", "MODEL",
                                "scopeRef", "expense_claim",
                                "conditionSpec", conditionSpec))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fragmentCode").value(code))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.fieldRefs[0]").value("record.data.amount"))
                .andExpect(jsonPath("$.data.fieldRefs[1]").value("actor.roles"))
                .andReturn().getResponse().getContentAsString();
        String fragmentPid = json.readTree(body).path("data").path("pid").asText();

        mockMvc.perform(get("/api/decision/condition-fragments/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pid").value(fragmentPid))
                .andExpect(jsonPath("$.data.conditionSpec.root.type").value("group"));

        mockMvc.perform(post("/api/decision/condition-fragments/" + code + "/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "context", Map.of(
                                        "record", Map.of("data", Map.of("amount", 20000)),
                                        "actor", Map.of("roles", List.of("manager")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.result").value("TRUE"));

        mockMvc.perform(post("/api/decision/condition-fragments/" + code + "/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "context", Map.of(
                                        "record", Map.of("data", Map.of("amount", 20000)),
                                        "actor", Map.of("roles", List.of("internal_auditor")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matched").value(false))
                .andExpect(jsonPath("$.data.result").value("FALSE"));

        DecisionUsageRefEntity ref = new DecisionUsageRefEntity();
        ref.setPid(UniqueIdGenerator.generate());
        ref.setTenantId(getTestTenant().getId());
        ref.setSourceType("SLA_RULE");
        ref.setSourceCode("it_sla_" + code);
        ref.setSourcePid(UniqueIdGenerator.generate());
        ref.setTargetType("CONDITION_FRAGMENT");
        ref.setTargetCode(code);
        ref.setBinding("condition");
        ref.setCreatedAt(Instant.now());
        ref.setUpdatedAt(Instant.now());
        usageRefMapper.insert(ref);

        mockMvc.perform(get("/api/decision/condition-fragments/" + code + "/impact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incomingCount").value(1))
                .andExpect(jsonPath("$.data.incoming[0].sourceType").value("SLA_RULE"))
                .andExpect(jsonPath("$.data.incoming[0].targetType").value("CONDITION_FRAGMENT"));
    }

    @Test
    void httpConditionFragments_reportDecisionConsumerImpactWhenFragmentReferencesDecision() throws Exception {
        String code = "it_fragment_decision_" + System.nanoTime();
        String decisionCode = "approval_routing_" + System.nanoTime();
        JsonNode conditionSpec = json.readTree("""
            { "root": { "type": "group", "op": "AND", "children": [] },
              "decisionBindings": [
                { "decisionCode": "%s", "versionPolicy": "LATEST_PUBLISHED", "enabled": true }
              ] }
            """.formatted(decisionCode));

        mockMvc.perform(post("/api/decision/condition-fragments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "fragmentCode", code,
                                "fragmentName", "Approval routing fragment",
                                "scopeType", "BPM",
                                "scopeRef", "wd_leave_approval",
                                "conditionSpec", conditionSpec))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionRefs[0]").value(decisionCode));

        DecisionUsageRefEntity decisionConsumer = new DecisionUsageRefEntity();
        decisionConsumer.setPid(UniqueIdGenerator.generate());
        decisionConsumer.setTenantId(getTestTenant().getId());
        decisionConsumer.setSourceType("BPM_PROCESS");
        decisionConsumer.setSourceCode("wd_leave_approval");
        decisionConsumer.setSourcePid(UniqueIdGenerator.generate());
        decisionConsumer.setTargetType("DECISION");
        decisionConsumer.setTargetCode(decisionCode);
        decisionConsumer.setBinding("RULE_BINDING");
        decisionConsumer.setMetadataJson(json.valueToTree(Map.of(
                "sourceName", "请假审批流程",
                "processKey", "wd_leave_approval")));
        decisionConsumer.setCreatedAt(Instant.now());
        decisionConsumer.setUpdatedAt(Instant.now());
        usageRefMapper.insert(decisionConsumer);

        mockMvc.perform(get("/api/decision/condition-fragments/" + code + "/impact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incomingCount").value(1))
                .andExpect(jsonPath("$.data.incoming[0].sourceType").value("BPM_PROCESS"))
                .andExpect(jsonPath("$.data.incoming[0].sourceName").value("请假审批流程"))
                .andExpect(jsonPath("$.data.incoming[0].targetType").value("DECISION"))
                .andExpect(jsonPath("$.data.incoming[0].targetCode").value(decisionCode));
    }

    @Test
    void httpConditionFragments_versionLifecycleDoesNotServeDrafts() throws Exception {
        String code = "it_fragment_version_" + System.nanoTime();

        String v1Body = mockMvc.perform(post("/api/decision/condition-fragments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "fragmentCode", code,
                                "fragmentName", "High value approval",
                                "scopeType", "MODEL",
                                "scopeRef", "expense_claim",
                                "conditionSpec", conditionSpecGreaterThan(10000)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        String v1Pid = json.readTree(v1Body).path("data").path("pid").asText();

        mockMvc.perform(post("/api/decision/condition-fragment-versions/" + v1Pid + "/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VALIDATED"));

        mockMvc.perform(post("/api/decision/condition-fragment-versions/" + v1Pid + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.version").value(1));

        String v2Body = mockMvc.perform(post("/api/decision/condition-fragments/" + code + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "fragmentName", "High value approval v2",
                                "description", "Lowered threshold after policy review",
                                "conditionSpec", conditionSpecGreaterThan(1000)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        String v2Pid = json.readTree(v2Body).path("data").path("pid").asText();

        mockMvc.perform(get("/api/decision/condition-fragments/" + code + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value(2))
                .andExpect(jsonPath("$.data[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data[1].version").value(1))
                .andExpect(jsonPath("$.data[1].status").value("PUBLISHED"));

        // v2 is still draft, so runtime evaluation must stay pinned to the latest bindable version (v1).
        mockMvc.perform(post("/api/decision/condition-fragments/" + code + "/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "context", Map.of("record", Map.of("data", Map.of("amount", 5000)))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.matched").value(false));

        mockMvc.perform(post("/api/decision/condition-fragment-versions/" + v2Pid + "/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VALIDATED"));

        mockMvc.perform(post("/api/decision/condition-fragment-versions/" + v2Pid + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(get("/api/decision/condition-fragments/" + code + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value(2))
                .andExpect(jsonPath("$.data[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data[1].version").value(1))
                .andExpect(jsonPath("$.data[1].status").value("DEPRECATED"));

        mockMvc.perform(post("/api/decision/condition-fragments/" + code + "/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "context", Map.of("record", Map.of("data", Map.of("amount", 5000)))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.matched").value(true));
    }

    private ExtensionBean extension(String key, String value) {
        ExtensionBean extension = new ExtensionBean();
        extension.setExtension(Map.of(key, value));
        return extension;
    }

    private Model createPublishedModel(String code, String name, String sourceType, String sourceRef) {
        Model model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(getTestTenant().getId());
        model.setCode(code);
        model.setExtension(extension("displayName", name));
        model.setTableName("mt_" + code);
        model.setSourceType(sourceType);
        model.setSourceRef(sourceRef);
        model.setVersion(1);
        model.setSemver("1.0.0");
        model.setRowVersion(1);
        model.setIsCurrent(true);
        model.setStatus("published");
        model.setDeletedFlag(false);
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        metaModelMapper.insert(model);
        return model;
    }

    private Field createPublishedField(String code, String dataType, String name) {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(getTestTenant().getId());
        field.setCode(code);
        field.setDataType(dataType);
        field.setExtension(extension("displayName", name));
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(false);
        feature.setUnique(false);
        field.setFeature(feature);
        field.setVersion(1);
        field.setSemver("1.0.0");
        field.setRowVersion(1);
        field.setIsCurrent(true);
        field.setStatus("published");
        field.setDeletedFlag(false);
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        metaFieldMapper.insert(field);
        return field;
    }

    private void bindFieldToModel(Model model, Field field, int order) {
        ModelFieldBinding binding = new ModelFieldBinding(getTestTenant().getId(), model.getId(), field.getId(), order);
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());
        metaModelFieldBindingMapper.insert(binding);
    }

    private Dict createPublishedDict(String code, String name) {
        Dict dict = new Dict();
        dict.setPid(UniqueIdGenerator.generate());
        dict.setTenantId(getTestTenant().getId());
        dict.setCode(code);
        dict.setName(name);
        dict.setDictType("static");
        dict.setStatus("published");
        dict.setVersion(1);
        dict.setSemver("1.0.0");
        dict.setIsCurrent(true);
        dict.setCreatedAt(Instant.now());
        dict.setUpdatedAt(Instant.now());
        dictMapper.insert(dict);
        return dict;
    }

    private void createDictItem(Dict dict, String value, String label) {
        DictItem item = new DictItem();
        item.setPid(UniqueIdGenerator.generate());
        item.setTenantId(getTestTenant().getId());
        item.setDictId(dict.getId());
        item.setValue(value);
        item.setLabel(label);
        item.setSortNo(0);
        item.setStatus("enabled");
        item.setSource("user");
        item.setCreatedAt(Instant.now());
        item.setUpdatedAt(Instant.now());
        dictItemMapper.insert(item);
    }

    private void bindFieldToDict(Field field, Dict dict) {
        FieldDictBinding binding = FieldDictBinding.builder()
                .pid(UniqueIdGenerator.generate())
                .fieldId(field.getId())
                .fieldPid(field.getPid())
                .fieldCode(field.getCode())
                .dictId(dict.getId())
                .dictCode(dict.getCode())
                .tenantId(getTestTenant().getId())
                .deletedFlag(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        metaFieldDictBindingMapper.insert(binding);
    }

    private JsonNode findEntity(JsonNode entities, String modelCode) {
        for (JsonNode entity : entities) {
            if (modelCode.equals(entity.path("modelCode").asText())) {
                return entity;
            }
        }
        return MissingNode.getInstance();
    }

    private JsonNode findEntityByScope(JsonNode entities, String scope) {
        for (JsonNode entity : entities) {
            if (scope.equals(entity.path("scope").asText())) {
                return entity;
            }
        }
        return MissingNode.getInstance();
    }

    private JsonNode findFact(JsonNode facts, String factKey) {
        for (JsonNode fact : facts) {
            if (factKey.equals(fact.path("factKey").asText())) {
                return fact;
            }
        }
        return MissingNode.getInstance();
    }

    private void assertAvailableAction(JsonNode actions, String actionType) {
        JsonNode action = findCatalogAction(actions, actionType);
        assertTrue(!action.isMissingNode());
        assertTrue(action.path("handlerAvailable").asBoolean());
        assertTrue("AVAILABLE".equals(action.path("availabilityStatus").asText()));
        assertTrue(action.path("availabilityReason").isMissingNode() || action.path("availabilityReason").isNull());
        assertTrue(action.path("consumerTypes").isArray());
        assertTrue(hasArrayText(action.path("consumerTypes"), "EVENT_POLICY"));
        assertProviderDependencyTypes(action.path("providerDependencies"), expectedProviderTypes(actionType));
        assertConsumerAvailability(action, "SLA", true);
        assertConsumerAvailability(action, "EVENT_POLICY", true);
        assertConsumerAvailability(action, "AUTOMATION", true);
        assertConsumerAvailability(action, "BPM", true);
        assertTrue(action.path("inputSchema").isObject());
    }

    private void assertUnavailableAction(JsonNode actions, String actionType) {
        JsonNode action = findCatalogAction(actions, actionType);
        assertTrue(!action.isMissingNode());
        assertTrue(!action.path("handlerAvailable").asBoolean());
        assertTrue("UNAVAILABLE".equals(action.path("availabilityStatus").asText()));
        assertTrue(action.path("availabilityReason").asText().length() > 0);
        assertTrue(action.path("consumerTypes").isArray());
        assertTrue(hasArrayText(action.path("consumerTypes"), "EVENT_POLICY"));
        if ("SEND_SMS".equals(actionType)) {
            assertSmsProviderDependency(action.path("providerDependencies"));
        }
        assertProviderDependencyTypes(action.path("providerDependencies"), expectedProviderTypes(actionType));
        assertConsumerAvailability(action, "SLA", false);
        assertConsumerAvailability(action, "EVENT_POLICY", false);
        assertConsumerAvailability(action, "AUTOMATION", false);
        assertConsumerAvailability(action, "BPM", false);
        assertTrue(action.path("inputSchema").isObject());
    }

    private void assertConsumerAvailability(JsonNode action, String consumerType, boolean available) {
        JsonNode availability = findConsumerAvailability(action.path("consumerAvailability"), consumerType);
        assertTrue(!availability.isMissingNode());
        assertTrue(consumerType.equals(availability.path("consumerType").asText()));
        assertTrue(availability.path("handlerAvailable").asBoolean() == available);
        assertTrue((available ? "AVAILABLE" : "UNAVAILABLE")
                .equals(availability.path("availabilityStatus").asText()));
        if (available) {
            assertTrue(availability.path("availabilityReason").isMissingNode()
                    || availability.path("availabilityReason").isNull());
            assertProviderDependencyTypes(
                    availability.path("providerDependencies"),
                    expectedProviderTypes(action.path("actionType").asText()));
        } else {
            assertTrue(availability.path("availabilityReason").asText().length() > 0);
            if ("SEND_SMS".equals(action.path("actionType").asText())) {
                assertSmsProviderDependency(availability.path("providerDependencies"));
            }
            assertProviderDependencyTypes(
                    availability.path("providerDependencies"),
                    expectedProviderTypes(action.path("actionType").asText()));
        }
    }

    private List<String> expectedProviderTypes(String actionType) {
        return switch (actionType) {
            case "NOTIFY" -> List.of("NOTIFICATION");
            case "SEND_SMS" -> List.of("SMS");
            case "SEND_IM" -> List.of("IM");
            case "WEBHOOK" -> List.of("WEBHOOK");
            case "START_PROCESS" -> List.of("BPM");
            case "CREATE_TASK" -> List.of("INBOX");
            case "CC_TASK" -> List.of("INBOX", "BPM");
            case "ADD_COMMENT" -> List.of("COMMENT");
            case "UPDATE_RECORD", "PATCH_RECORD" -> List.of("LOWCODE_MODEL");
            case "WRITE_AUDIT" -> List.of("AUDIT");
            default -> List.of();
        };
    }

    private void assertProviderDependencyTypes(JsonNode providerDependencies, List<String> providerTypes) {
        assertTrue(providerDependencies.isArray());
        for (String providerType : providerTypes) {
            boolean found = false;
            for (JsonNode dependency : providerDependencies) {
                if (providerType.equals(dependency.path("providerType").asText())) {
                    found = true;
                    assertTrue(dependency.path("label").asText().length() > 0);
                    assertTrue(dependency.path("required").asBoolean());
                    assertTrue(dependency.path("availabilityStatus").asText().length() > 0);
                    break;
                }
            }
            assertTrue(found, "Missing provider dependency type: " + providerType);
        }
    }

    private void assertSmsProviderDependency(JsonNode providerDependencies) {
        assertTrue(providerDependencies.isArray());
        JsonNode dependency = MissingNode.getInstance();
        for (JsonNode candidate : providerDependencies) {
            if ("SMS".equals(candidate.path("providerType").asText())) {
                dependency = candidate;
                break;
            }
        }
        assertTrue(!dependency.isMissingNode());
        assertTrue("真实短信 provider".equals(dependency.path("label").asText()));
        assertTrue(dependency.path("required").asBoolean());
        assertTrue(!dependency.path("available").asBoolean());
        assertTrue("UNAVAILABLE".equals(dependency.path("availabilityStatus").asText()));
        assertTrue(dependency.path("availabilityReason").asText().contains("真实短信 provider"));
    }

    private JsonNode findConsumerAvailability(JsonNode availabilityRows, String consumerType) {
        for (JsonNode row : availabilityRows) {
            if (consumerType.equals(row.path("consumerType").asText())) {
                return row;
            }
        }
        return MissingNode.getInstance();
    }

    private JsonNode findCatalogAction(JsonNode actions, String actionType) {
        for (JsonNode action : actions) {
            if (actionType.equals(action.path("actionType").asText())) {
                return action;
            }
        }
        return MissingNode.getInstance();
    }

    private boolean hasArrayText(JsonNode array, String expected) {
        for (JsonNode item : array) {
            if (expected.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOption(JsonNode array, String value, String label) {
        for (JsonNode item : array) {
            if (value.equals(item.path("value").asText()) && label.equals(item.path("label").asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode conditionSpecGreaterThan(int threshold) throws Exception {
        return json.readTree("""
            { "root": {
                "type": "compare",
                "left": { "type": "path", "scope": "record", "path": "data.amount", "dataType": "decimal" },
                "operator": "GT",
                "right": { "type": "literal", "value": %d, "dataType": "decimal" } },
              "decisionBindings": [] }
            """.formatted(threshold));
    }

    @Test
    void httpRecentLogs_returnsTenantScopedNewestEvaluationLogsForDslList() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        DrtLogEntity older = new DrtLogEntity();
        older.setPid(UniqueIdGenerator.generate());
        older.setTenantId(getTestTenant().getId());
        older.setTraceId("trace-recent-old-" + suffix);
        older.setDecisionCode("recent_old_" + suffix);
        older.setDecisionVersion(1);
        older.setKind("SIMPLE_CONDITION");
        older.setRuntimeAdapter("AST_EVALUATOR");
        older.setCallerType("API");
        older.setRolloutArm("BASELINE");
        older.setMatched(false);
        older.setStatus("NOT_MATCHED");
        older.setDurationMs(31L);
        older.setCreatedAt(Instant.now().plusSeconds(60));
        logMapper.insert(older);

        DrtLogEntity newest = new DrtLogEntity();
        newest.setPid(UniqueIdGenerator.generate());
        newest.setTenantId(getTestTenant().getId());
        newest.setTraceId("trace-recent-new-" + suffix);
        newest.setDecisionCode("recent_new_" + suffix);
        newest.setDecisionVersion(2);
        newest.setKind("SIMPLE_CONDITION");
        newest.setRuntimeAdapter("AST_EVALUATOR");
        newest.setCallerType("AUTOMATION");
        newest.setCallerRef("policy_" + suffix);
        newest.setRolloutArm("CANDIDATE");
        newest.setMatched(true);
        newest.setStatus("MATCHED");
        newest.setOutputSnapshot(json.valueToTree(Map.of(
                "deadlineMinutes", 45,
                "severity", "warning")));
        newest.setDurationMs(12L);
        newest.setCreatedAt(Instant.now().plusSeconds(120));
        logMapper.insert(newest);

        mockMvc.perform(get("/api/decision/logs/recent")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].traceId").value(newest.getTraceId()))
                .andExpect(jsonPath("$.data.records[0].decisionCode").value(newest.getDecisionCode()))
                .andExpect(jsonPath("$.data.records[0].status").value("MATCHED"))
                .andExpect(jsonPath("$.data.total").isNumber());

        mockMvc.perform(get("/api/decision/logs/recent")
                        .param("keyword", suffix)
                        .param("callerType", "automation")
                        .param("callerRef", newest.getCallerRef())
                        .param("matched", "true")
                        .param("rolloutArm", "candidate")
                        .param("minDurationMs", "10")
                        .param("maxDurationMs", "20")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].traceId").value(newest.getTraceId()))
                .andExpect(jsonPath("$.data.records[0].callerType").value("AUTOMATION"))
                .andExpect(jsonPath("$.data.records[0].callerRef").value(newest.getCallerRef()))
                .andExpect(jsonPath("$.data.records[0].outputSnapshot.deadlineMinutes").value(45))
                .andExpect(jsonPath("$.data.records[0].outputSnapshot.severity").value("warning"))
                .andExpect(jsonPath("$.data.records[0].rolloutArm").value("CANDIDATE"));

        mockMvc.perform(get("/api/decision/logs/recent")
                        .param("callerType", "automation")
                        .param("callerRef", "missing_" + suffix)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(0));
    }

    @Test
    void httpLogDetailAndTraceQuery_areTenantScopedForDslDetail() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String traceId = "trace-detail-" + suffix;

        DrtLogEntity own = createLog(
                getTestTenant().getId(),
                traceId,
                "detail_own_" + suffix,
                "MATCHED",
                true,
                19L,
                json.valueToTree(Map.of(
                        "candidateUserIds", List.of("u-manager"),
                        "deadlineMinutes", 30)));
        createLog(
                getTestTenant().getId() + 999_999L,
                traceId,
                "detail_foreign_" + suffix,
                "ERROR",
                false,
                99L);

        mockMvc.perform(get("/api/decision/logs/" + own.getPid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.pid").value(own.getPid()))
                .andExpect(jsonPath("$.data.traceId").value(traceId))
                .andExpect(jsonPath("$.data.decisionCode").value("detail_own_" + suffix))
                .andExpect(jsonPath("$.data.status").value("MATCHED"))
                .andExpect(jsonPath("$.data.outputSnapshot.candidateUserIds[0]").value("u-manager"))
                .andExpect(jsonPath("$.data.outputSnapshot.deadlineMinutes").value(30));

        mockMvc.perform(get("/api/decision/logs")
                        .param("traceId", traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].pid").value(own.getPid()))
                .andExpect(jsonPath("$.data[0].decisionCode").value("detail_own_" + suffix))
                .andExpect(jsonPath("$.data[0].outputSnapshot.candidateUserIds[0]").value("u-manager"))
                .andExpect(jsonPath("$.data[0].outputSnapshot.deadlineMinutes").value(30));
    }

    @Test
    void httpPermissionMatrix_projectsDecisionRuntimeRoleGrants() throws Exception {
        grant("decision.definition.approve", "decision", "definition", "approve", "Decision Definition Approve");
        grant("decision.rollout.manage", "decision", "rollout", "manage", "Decision Rollout Manage");
        grant("decision.rollout.promote", "decision", "rollout", "promote", "Decision Rollout Promote");
        grant("decision.rollout.rollback", "decision", "rollout", "rollback", "Decision Rollout Rollback");

        String body = mockMvc.perform(get("/api/decision/permissions/matrix"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = json.readTree(body).path("data");
        boolean foundRole = false;
        for (JsonNode row : data.path("roles")) {
            if (getTestRole().getName().equals(row.path("role").asText())) {
                foundRole = true;
                assertTrue(row.path("caps").path("view").asBoolean());
                assertTrue(row.path("caps").path("test").asBoolean());
                assertTrue(row.path("caps").path("publish").asBoolean());
                assertTrue(row.path("caps").path("approve").asBoolean());
                assertTrue(row.path("caps").path("rolloutManage").asBoolean());
                assertTrue(row.path("caps").path("rolloutPromote").asBoolean());
                assertTrue(row.path("caps").path("rolloutRollback").asBoolean());
                assertTrue(row.path("capabilities").path("approve").path("permissionCode")
                        .asText().equals("decision.definition.approve"));
                assertTrue(row.path("capabilities").path("rolloutPromote").path("permissionCode")
                        .asText().equals("decision.rollout.promote"));
            }
        }
        assertTrue(foundRole);
    }

    @Test
    void httpRolloutLifecycleRequiresDedicatedPermissions() throws Exception {
        mockMvc.perform(post("/api/decision/definitions/decision_without_rollout_permission/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "baselineVersion", 1,
                                "candidateVersion", 2,
                                "percentage", 10,
                                "routingKeyExpr", "traceId"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/decision/rollouts/rollout_without_promote_permission/promote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("note", "promote attempt"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/decision/rollouts/rollout_without_rollback_permission/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("note", "rollback attempt"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void httpDefinitionLifecyclePublishActionsRequirePublishPermission() throws Exception {
        revoke("decision.definition.publish");

        mockMvc.perform(post("/api/decision/versions/no_publish_permission/publish"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.context").value(org.hamcrest.Matchers.containsString(
                        "decision.definition.publish")));

        mockMvc.perform(post("/api/decision/versions/no_publish_permission/submit-for-approval"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.context").value(org.hamcrest.Matchers.containsString(
                        "decision.definition.publish")));

        mockMvc.perform(post("/api/decision/versions/no_publish_permission/deprecate"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.context").value(org.hamcrest.Matchers.containsString(
                        "decision.definition.publish")));
    }

    @Test
    void httpDefinitionLifecycleApprovalActionsRequireApprovePermission() throws Exception {
        revoke("decision.definition.approve");

        mockMvc.perform(post("/api/decision/versions/no_approve_permission/approve")
                        .param("note", "approve attempt"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.context").value(org.hamcrest.Matchers.containsString(
                        "decision.definition.approve")));

        mockMvc.perform(post("/api/decision/versions/no_approve_permission/reject")
                        .param("note", "reject attempt"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.context").value(org.hamcrest.Matchers.containsString(
                        "decision.definition.approve")));
    }

    @Test
    void httpRolloutListAndDetailExposeDslApiDatasourceShape() throws Exception {
        grant("decision.rollout.manage", "decision", "rollout", "manage", "Decision Rollout Manage");
        userPermissionService.evictUserPermissions(getTestUser().getId());

        String code = "it_rollout_api_" + System.nanoTime();
        createDefinition(code);
        createPublishedVersion(code);
        createPublishedVersion(code);

        String createBody = mockMvc.perform(post("/api/decision/definitions/" + code + "/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "baselineVersion", 1,
                                "candidateVersion", 2,
                                "percentage", 15,
                                "routingKeyExpr", "traceId",
                                "salt", "rollout-list-detail-it"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionCode").value(code))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        String rolloutPid = json.readTree(createBody).path("data").path("pid").asText();

        mockMvc.perform(get("/api/decision/rollouts")
                        .param("decisionCode", code)
                        .param("status", "DRAFT")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].pid").value(rolloutPid))
                .andExpect(jsonPath("$.data.records[0].decisionCode").value(code))
                .andExpect(jsonPath("$.data.records[0].baselineVersion").value(1))
                .andExpect(jsonPath("$.data.records[0].candidateVersion").value(2))
                .andExpect(jsonPath("$.data.records[0].percentage").value(15))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/decision/rollouts/" + rolloutPid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pid").value(rolloutPid))
                .andExpect(jsonPath("$.data.decisionCode").value(code))
                .andExpect(jsonPath("$.data.salt").value("rollout-list-detail-it"));
    }

    @Test
    void httpDecisionImpact_returnsConsumersAndOutgoingRefs() throws Exception {
        ImpactFixture fixture = seedDecisionImpactFixture();

        String body = mockMvc.perform(get("/api/decision/definitions/" + fixture.decisionCode() + "/impact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionCode").value(fixture.decisionCode()))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = json.readTree(body).path("data");
        assertTrue(data.path("risk").path("blocking").asBoolean());
        assertTrue(data.path("risk").path("summary").asText().contains("automation"));
        assertTrue(data.path("incoming").findValuesAsText("sourceType").contains("AUTOMATION"));
        assertTrue(data.path("incoming").findValuesAsText("sourceType").contains("SLA_RULE"));
        assertTrue(data.path("incoming").findValuesAsText("sourceType").contains("EVENT_POLICY"));
        assertTrue(data.path("outgoing").findValuesAsText("targetPath").contains("record.data.amount"));
    }

    @Test
    void httpDecisionUsageIndexRebuild_andFieldImpactExposeIndexedRefs() throws Exception {
        ImpactFixture fixture = seedDecisionImpactFixture();

        mockMvc.perform(post("/api/decision/usage-index/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRefs").isNumber())
                .andExpect(jsonPath("$.data.consumerRefs").value(3))
                .andExpect(jsonPath("$.data.fieldRefs").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(3)));

        String body = mockMvc.perform(get("/api/decision/fields/impact")
                        .param("fieldRef", "record.data.amount"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldRef").value("record.data.amount"))
                .andExpect(jsonPath("$.data.risk.blocking").value(true))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = json.readTree(body).path("data");
        assertTrue(data.path("references").findValuesAsText("sourceType").contains("DECISION_VERSION"));
        assertTrue(data.path("references").findValuesAsText("sourceCode").contains(fixture.decisionCode()));
        assertTrue(data.path("references").findValuesAsText("targetPath").contains("record.data.amount"));
    }

    @Test
    void httpDecisionImpactIncludesAutomationRuleBindingRefs() throws Exception {
        String code = "it_rule_binding_impact_" + System.nanoTime();
        createDefinition(code);
        createPublishedVersion(code);

        Automation automation = new Automation();
        automation.setPid(UniqueIdGenerator.generate());
        automation.setTenantId(getTestTenant().getId());
        automation.setName("Rule Binding Impact Automation");
        automation.setDescription("Automation using platform RuleConsumerBinding");
        automation.setModelCode("complaint");
        automation.setTriggerType("on_record_create");
        automation.setTriggerConfig(TriggerConfig.builder()
                .modelCode("complaint")
                .ruleBinding(new RuleConsumerBinding(
                        "AUTOMATION",
                        "auto-rule-binding-impact",
                        "trigger",
                        RuleBindingKind.DECISION_REF,
                        null,
                        new DecisionBinding(
                                code,
                                DecisionVersionPolicy.ROLLOUT,
                                null,
                                null,
                                null,
                                List.of(new DecisionBinding.InputMapping(
                                        "amount",
                                        RuleValueSource.field(Scope.RECORD, "data.amount"))),
                                List.of(new DecisionBinding.OutputMapping(
                                        "route",
                                        new RuleMappingTarget(RuleMappingTarget.Kind.ACTION_PARAM, "route"))),
                                DecisionBinding.FallbackPolicy.failClosed(),
                                200,
                                DecisionBinding.TraceMode.ALWAYS,
                                true,
                                RuleValueSource.field(Scope.RECORD, "data.recordPid"),
                                null),
                        true))
                .build());
        automation.setEnabled(true);
        automation.setActions(List.of());
        automation.setTriggerCount(0L);
        automation.setDeletedFlag(false);
        automation.setCreatedAt(Instant.now());
        automation.setUpdatedAt(Instant.now());
        automation.setCreatedBy(getTestUser().getPid());
        automation.setUpdatedBy(getTestUser().getPid());
        automationMapper.insertAutomation(automation);

        mockMvc.perform(post("/api/decision/usage-index/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consumerRefs").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.fieldRefs").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(2)));

        String impactBody = mockMvc.perform(get("/api/decision/definitions/" + code + "/impact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionCode").value(code))
                .andExpect(jsonPath("$.data.risk.blocking").value(true))
                .andReturn().getResponse().getContentAsString();

        JsonNode impact = json.readTree(impactBody).path("data");
        assertTrue(impact.path("incoming").findValuesAsText("sourceType").contains("AUTOMATION"));
        assertTrue(impact.path("incoming").findValuesAsText("sourcePid").contains(automation.getPid()));
        assertTrue(impact.path("incoming").findValuesAsText("binding").contains("RULE_BINDING"));

        String fieldBody = mockMvc.perform(get("/api/decision/fields/impact")
                        .param("fieldRef", "record.data.recordPid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldRef").value("record.data.recordPid"))
                .andExpect(jsonPath("$.data.risk.blocking").value(true))
                .andReturn().getResponse().getContentAsString();

        JsonNode fieldImpact = json.readTree(fieldBody).path("data");
        assertTrue(fieldImpact.path("references").findValuesAsText("sourceType").contains("AUTOMATION"));
        assertTrue(fieldImpact.path("references").findValuesAsText("sourcePid").contains(automation.getPid()));
        assertTrue(fieldImpact.path("references").findValuesAsText("binding").contains("RULE_BINDING"));
    }

    @Test
    void httpDecisionImpactIncludesBpmDesignerNodeRefs() throws Exception {
        String code = "it_bpm_rule_binding_impact_" + System.nanoTime();
        createDefinition(code);
        createPublishedVersion(code);

        String processPid = UniqueIdGenerator.generate();
        String processKey = "bpm_rule_center_" + System.nanoTime();
        BpmProcessDefinition process = BpmProcessDefinition.builder()
                .pid(processPid)
                .tenantId(getTestTenant().getId())
                .processKey(processKey)
                .processName("BPM Rule Center Impact")
                .category("test")
                .bpmnContent("")
                .formBindings(Map.of())
                .businessDataBindings(Map.of())
                .extension(Map.of("designerJson", """
                        {
                          "key": "%s",
                          "name": "BPM Rule Center Impact",
                          "nodes": [
                            {
                              "id": "gateway_route",
                              "type": "exclusiveGateway",
                              "data": {
                                "type": "exclusiveGateway",
                                "label": "Route",
                                "config": {
                                  "ruleBinding": {
                                    "consumerType": "BPM",
                                    "consumerCode": "%s",
                                    "consumerNodeId": "gateway_route",
                                    "bindingKind": "DECISION_REF",
                                    "decisionBinding": {
                                      "decisionCode": "%s",
                                      "versionPolicy": "ROLLOUT",
                                      "inputMappings": [
                                        {
                                          "input": "amount",
                                          "source": { "kind": "field", "scope": "record", "path": "amount" }
                                        }
                                      ]
                                    },
                                    "enabled": true
                                  }
                                }
                              }
                            },
                            {
                              "id": "task_assign",
                              "type": "userTask",
                              "data": { "type": "userTask", "label": "Approve" }
                            }
                          ],
                          "edges": [
                            {
                              "id": "edge_high_amount",
                              "source": "gateway_route",
                              "target": "task_assign",
                              "data": {
                                "label": "High amount",
                                "conditionSpec": {
                                  "root": {
                                    "type": "compare",
                                    "left": {
                                      "type": "path",
                                      "scope": "record",
                                      "path": "amount",
                                      "dataType": "decimal"
                                    },
                                    "operator": "GTE",
                                    "right": { "type": "literal", "value": 1000, "dataType": "decimal" }
                                  }
                                }
                              }
                            }
                          ]
                        }
                        """.formatted(processKey, processKey, code)))
                .status("draft")
                .version(1)
                .isCurrent(true)
                .deletedFlag(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .createdBy(getTestUser().getId())
                .updatedBy(getTestUser().getId())
                .build();
        bpmProcessDefinitionMapper.insert(process);

        mockMvc.perform(post("/api/decision/usage-index/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consumerRefs").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.fieldRefs").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        String impactBody = mockMvc.perform(get("/api/decision/definitions/" + code + "/impact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionCode").value(code))
                .andExpect(jsonPath("$.data.risk.blocking").value(true))
                .andReturn().getResponse().getContentAsString();

        JsonNode impact = json.readTree(impactBody).path("data");
        assertTrue(impact.path("incoming").findValuesAsText("sourceType").contains("BPM_PROCESS"));
        assertTrue(impact.path("incoming").findValuesAsText("sourcePid").contains(processPid));
        assertTrue(impact.path("incoming").findValuesAsText("binding").contains("DESIGNER_NODE"));
        assertTrue(hasReferenceWithMetadata(
                impact.path("incoming"), "BPM_PROCESS", processPid, "nodeId", "gateway_route"));

        String fieldBody = mockMvc.perform(get("/api/decision/fields/impact")
                        .param("fieldRef", "record.amount"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldRef").value("record.amount"))
                .andExpect(jsonPath("$.data.risk.blocking").value(true))
                .andReturn().getResponse().getContentAsString();

        JsonNode fieldImpact = json.readTree(fieldBody).path("data");
        assertTrue(fieldImpact.path("references").findValuesAsText("sourceType").contains("BPM_PROCESS"));
        assertTrue(fieldImpact.path("references").findValuesAsText("sourcePid").contains(processPid));
        assertTrue(hasReferenceWithMetadata(
                fieldImpact.path("references"), "BPM_PROCESS", processPid, "edgeId", "edge_high_amount"));
    }

    @Test
    void httpFieldChangePreflightBlocksReferencedFieldUntilAcknowledged() throws Exception {
        seedDecisionImpactFixture();

        mockMvc.perform(post("/api/decision/fields/preflight").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "fieldRef", "record.data.amount",
                                "action", "DELETE_FIELD"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldRef").value("record.data.amount"))
                .andExpect(jsonPath("$.data.allowed").value(false))
                .andExpect(jsonPath("$.data.blocked").value(true))
                .andExpect(jsonPath("$.data.requiresAcknowledgement").value(true))
                .andExpect(jsonPath("$.data.risk.blocking").value(true))
                .andExpect(jsonPath("$.data.references[0].sourceType").value("DECISION_VERSION"));

        mockMvc.perform(post("/api/decision/fields/preflight").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "fieldRef", "record.data.amount",
                                "action", "DELETE_FIELD",
                                "impactAcknowledged", true,
                                "note", "schema migration approved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(true))
                .andExpect(jsonPath("$.data.blocked").value(false))
                .andExpect(jsonPath("$.data.requiresAcknowledgement").value(true));

        applyTestMetaContext();
        List<DecisionImpactAckEntity> fieldAcks = impactAckMapper.selectList(
                new LambdaQueryWrapper<DecisionImpactAckEntity>()
                        .eq(DecisionImpactAckEntity::getTenantId, getTestTenant().getId())
                        .eq(DecisionImpactAckEntity::getActionType, "FIELD_DELETE")
                        .eq(DecisionImpactAckEntity::getTargetPath, "record.data.amount"));
        assertTrue(fieldAcks.stream().anyMatch(ack ->
                ack.getImpactSummary().contains("decision version")
                        && "schema migration approved".equals(ack.getNote())));

        mockMvc.perform(post("/api/decision/fields/preflight").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "fieldRef", "record.data.amount",
                                "action", "DELETE_DICT_ITEM",
                                "dictCode", "leave_type",
                                "dictValue", "annual",
                                "impactAcknowledged", true,
                                "note", "dict value migration approved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(true))
                .andExpect(jsonPath("$.data.blocked").value(false))
                .andExpect(jsonPath("$.data.action").value("DELETE_DICT_ITEM"))
                .andExpect(jsonPath("$.data.dictCode").value("leave_type"))
                .andExpect(jsonPath("$.data.dictValue").value("annual"))
                .andExpect(jsonPath("$.data.references[0].sourceType").value("DECISION_VERSION"));

        applyTestMetaContext();
        List<DecisionImpactAckEntity> dictAcks = impactAckMapper.selectList(
                new LambdaQueryWrapper<DecisionImpactAckEntity>()
                        .eq(DecisionImpactAckEntity::getTenantId, getTestTenant().getId())
                        .eq(DecisionImpactAckEntity::getActionType, "FIELD_DICT_ITEM_DELETE")
                        .eq(DecisionImpactAckEntity::getTargetPath, "record.data.amount"));
        assertTrue(dictAcks.stream().anyMatch(ack ->
                ack.getImpactSummary().contains("decision version")
                        && "dict value migration approved".equals(ack.getNote())));

        mockMvc.perform(post("/api/decision/fields/preflight").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "fieldRef", "record.data.amount",
                                "action", "CHANGE_DATA_TYPE",
                                "currentDataType", "decimal",
                                "nextDataType", "string"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(false))
                .andExpect(jsonPath("$.data.blocked").value(true))
                .andExpect(jsonPath("$.data.requiresAcknowledgement").value(true))
                .andExpect(jsonPath("$.data.action").value("CHANGE_DATA_TYPE"))
                .andExpect(jsonPath("$.data.currentDataType").value("decimal"))
                .andExpect(jsonPath("$.data.nextDataType").value("string"))
                .andExpect(jsonPath("$.data.references[0].sourceType").value("DECISION_VERSION"));

        mockMvc.perform(post("/api/decision/fields/preflight").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "fieldRef", "record.data.amount",
                                "action", "CHANGE_DATA_TYPE",
                                "currentDataType", "decimal",
                                "nextDataType", "string",
                                "impactAcknowledged", true,
                                "note", "type migration approved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(true))
                .andExpect(jsonPath("$.data.blocked").value(false))
                .andExpect(jsonPath("$.data.requiresAcknowledgement").value(true))
                .andExpect(jsonPath("$.data.action").value("CHANGE_DATA_TYPE"));

        applyTestMetaContext();
        List<DecisionImpactAckEntity> typeAcks = impactAckMapper.selectList(
                new LambdaQueryWrapper<DecisionImpactAckEntity>()
                        .eq(DecisionImpactAckEntity::getTenantId, getTestTenant().getId())
                        .eq(DecisionImpactAckEntity::getActionType, "FIELD_TYPE_CHANGE")
                        .eq(DecisionImpactAckEntity::getTargetPath, "record.data.amount"));
        assertTrue(typeAcks.stream().anyMatch(ack ->
                ack.getImpactSummary().contains("decision version")
                        && "type migration approved".equals(ack.getNote())));

        mockMvc.perform(post("/api/decision/fields/preflight").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "fieldRef", "record.data.unused",
                                "action", "CHANGE_TYPE",
                                "currentDataType", "decimal",
                                "nextDataType", "integer"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("CHANGE_DATA_TYPE"))
                .andExpect(jsonPath("$.data.allowed").value(true))
                .andExpect(jsonPath("$.data.blocked").value(false))
                .andExpect(jsonPath("$.data.requiresAcknowledgement").value(false));
    }

    @Test
    void httpValidateRefreshesOnlyCurrentDecisionVersionUsageRefs() throws Exception {
        ImpactFixture fixture = seedDecisionImpactFixture();
        mockMvc.perform(post("/api/decision/usage-index/rebuild"))
                .andExpect(status().isOk());

        DecisionUsageRefEntity sentinel = new DecisionUsageRefEntity();
        sentinel.setPid(UniqueIdGenerator.generate());
        sentinel.setTenantId(getTestTenant().getId());
        sentinel.setSourceType("EXTERNAL_SENTINEL");
        sentinel.setSourceCode("sentinel-source");
        sentinel.setSourcePid("sentinel-pid");
        sentinel.setTargetType("FIELD");
        sentinel.setTargetPath("sentinel.field");
        sentinel.setBinding("TEST");
        sentinel.setCreatedAt(Instant.now());
        sentinel.setUpdatedAt(Instant.now());
        usageRefMapper.insert(sentinel);

        String pid = createValidatedVersion(fixture.decisionCode());

        applyTestMetaContext();
        List<DecisionUsageRefEntity> sentinelRefs =
                usageRefMapper.findFieldRefs(getTestTenant().getId(), "sentinel.field");
        assertTrue(sentinelRefs.stream().anyMatch(ref -> "EXTERNAL_SENTINEL".equals(ref.getSourceType())));

        List<DecisionUsageRefEntity> amountRefs =
                usageRefMapper.findFieldRefs(getTestTenant().getId(), "record.data.amount");
        assertTrue(amountRefs.stream().anyMatch(ref -> pid.equals(ref.getSourcePid())));
    }

    @Test
    void httpUsageIndexRefreshSourceAndDeleteSourceOnlyTouchRequestedSource() throws Exception {
        ImpactFixture fixture = seedDecisionImpactFixture();
        mockMvc.perform(post("/api/decision/usage-index/rebuild"))
                .andExpect(status().isOk());

        applyTestMetaContext();
        Automation automation = automationMapper.findByPid(fixture.automationPid());
        automation.setTriggerConfig(TriggerConfig.builder()
                .modelCode("complaint")
                .decisionRef(fixture.decisionCode() + "_next")
                .decisionBinding("LATEST")
                .build());
        automation.setUpdatedAt(Instant.now());
        automationMapper.updateAutomation(automation);

        mockMvc.perform(post("/api/decision/usage-index/sources/AUTOMATION/" + fixture.automationPid() + "/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consumerRefs").value(1));

        applyTestMetaContext();
        List<DecisionUsageRefEntity> oldIncoming =
                usageRefMapper.findIncomingDecisionRefs(getTestTenant().getId(), fixture.decisionCode());
        assertTrue(oldIncoming.stream().noneMatch(ref -> "AUTOMATION".equals(ref.getSourceType())
                && fixture.automationPid().equals(ref.getSourcePid())));
        assertTrue(oldIncoming.stream().anyMatch(ref -> "SLA_RULE".equals(ref.getSourceType())
                && fixture.slaPid().equals(ref.getSourcePid())));
        assertTrue(oldIncoming.stream().anyMatch(ref -> "EVENT_POLICY".equals(ref.getSourceType())
                && fixture.policyVersionPid().equals(ref.getSourcePid())));

        List<DecisionUsageRefEntity> newIncoming =
                usageRefMapper.findIncomingDecisionRefs(getTestTenant().getId(), fixture.decisionCode() + "_next");
        assertTrue(newIncoming.stream().anyMatch(ref -> "AUTOMATION".equals(ref.getSourceType())
                && fixture.automationPid().equals(ref.getSourcePid())));

        mockMvc.perform(delete("/api/decision/usage-index/sources/AUTOMATION/" + fixture.automationPid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRefs").value(0));

        applyTestMetaContext();
        List<DecisionUsageRefEntity> deletedIncoming =
                usageRefMapper.findIncomingDecisionRefs(getTestTenant().getId(), fixture.decisionCode() + "_next");
        assertTrue(deletedIncoming.stream().noneMatch(ref -> "AUTOMATION".equals(ref.getSourceType())
                && fixture.automationPid().equals(ref.getSourcePid())));
    }

    @Test
    void httpPublishRequiresImpactAcknowledgementWhenDecisionHasConsumers() throws Exception {
        ImpactFixture fixture = seedDecisionImpactFixture();
        String pid = createValidatedVersion(fixture.decisionCode());

        mockMvc.perform(post("/api/decision/versions/" + pid + "/publish"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.context.error").value(org.hamcrest.Matchers.containsString(
                        "Impact acknowledgement required")));

        mockMvc.perform(post("/api/decision/versions/" + pid + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("impactAcknowledged", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void httpDeprecateAndRetireRequireImpactAcknowledgementWhenDecisionHasConsumers() throws Exception {
        ImpactFixture fixture = seedDecisionImpactFixture();

        mockMvc.perform(post("/api/decision/versions/" + fixture.publishedVersionPid() + "/deprecate"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.context.error").value(org.hamcrest.Matchers.containsString(
                        "Impact acknowledgement required")));

        mockMvc.perform(post("/api/decision/versions/" + fixture.publishedVersionPid() + "/deprecate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("impactAcknowledged", true, "note", "planned retirement"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEPRECATED"));

        applyTestMetaContext();
        List<DecisionImpactAckEntity> deprecateAcks = impactAckMapper.selectList(
                new LambdaQueryWrapper<DecisionImpactAckEntity>()
                        .eq(DecisionImpactAckEntity::getTenantId, getTestTenant().getId())
                        .eq(DecisionImpactAckEntity::getActionType, "DEPRECATE")
                        .eq(DecisionImpactAckEntity::getTargetPid, fixture.publishedVersionPid()));
        assertTrue(deprecateAcks.stream().anyMatch(ack ->
                ack.getImpactSummary().contains("automation")
                        && "planned retirement".equals(ack.getNote())));

        mockMvc.perform(post("/api/decision/versions/" + fixture.publishedVersionPid() + "/retire"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.context.error").value(org.hamcrest.Matchers.containsString(
                        "Impact acknowledgement required")));

        mockMvc.perform(post("/api/decision/versions/" + fixture.publishedVersionPid() + "/retire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("impactAcknowledged", true, "note", "replace by v2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETIRED"));

        applyTestMetaContext();
        List<DecisionImpactAckEntity> retireAcks = impactAckMapper.selectList(
                new LambdaQueryWrapper<DecisionImpactAckEntity>()
                        .eq(DecisionImpactAckEntity::getTenantId, getTestTenant().getId())
                        .eq(DecisionImpactAckEntity::getActionType, "RETIRE")
                        .eq(DecisionImpactAckEntity::getTargetPid, fixture.publishedVersionPid()));
        assertTrue(retireAcks.stream().anyMatch(ack ->
                ack.getImpactSummary().contains("automation")
                        && "replace by v2".equals(ack.getNote())));
    }

    @Test
    void httpDeleteVersionAllowsDraftLikeVersionsAndClearsUsageIndexSource() throws Exception {
        String code = "it_delete_" + System.nanoTime();
        createDefinition(code);
        String pid = createValidatedVersion(code);

        applyTestMetaContext();
        List<DecisionUsageRefEntity> refsBeforeDelete =
                usageRefMapper.findFieldRefs(getTestTenant().getId(), "record.data.amount");
        assertTrue(refsBeforeDelete.stream().anyMatch(ref -> pid.equals(ref.getSourcePid())));

        mockMvc.perform(delete("/api/decision/versions/" + pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pid").value(pid))
                .andExpect(jsonPath("$.data.status").value("VALIDATED"));

        applyTestMetaContext();
        List<DecisionUsageRefEntity> refsAfterDelete =
                usageRefMapper.findFieldRefs(getTestTenant().getId(), "record.data.amount");
        assertTrue(refsAfterDelete.stream().noneMatch(ref -> pid.equals(ref.getSourcePid())));
    }

    @Test
    void httpDeleteVersionRejectsPublishedVersions() throws Exception {
        String code = "it_delete_published_" + System.nanoTime();
        createDefinition(code);
        String pid = createValidatedVersion(code);
        mockMvc.perform(post("/api/decision/versions/" + pid + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        mockMvc.perform(delete("/api/decision/versions/" + pid))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.context.error").value(org.hamcrest.Matchers.containsString(
                        "Cannot delete")))
                .andExpect(jsonPath("$.context.error").value(org.hamcrest.Matchers.containsString(
                        "deprecate/retire")));
    }

    private ImpactFixture seedDecisionImpactFixture() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String code = "it_impact_" + suffix;

        createDefinition(code);

        String draftBody = mockMvc.perform(
                        post("/api/decision/definitions/" + code + "/versions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "kind", "SIMPLE_CONDITION",
                                        "runtimeAdapter", "AST_EVALUATOR",
                                        "contentJson", json.readTree(AST)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pid = json.readTree(draftBody).path("data").path("pid").asText();
        mockMvc.perform(post("/api/decision/versions/" + pid + "/validate"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/decision/versions/" + pid + "/publish"))
                .andExpect(status().isOk());

        Automation automation = new Automation();
        automation.setPid(UniqueIdGenerator.generate());
        automation.setTenantId(getTestTenant().getId());
        automation.setName("Impact Automation");
        automation.setDescription("Decision impact test automation");
        automation.setModelCode("complaint");
        automation.setTriggerType("on_record_create");
        automation.setTriggerConfig(TriggerConfig.builder()
                .modelCode("complaint")
                .decisionRef(code)
                .decisionBinding("LATEST")
                .ruleBinding(fieldRuleBinding(
                        "AUTOMATION",
                        "impact-automation",
                        "trigger",
                        "data.amount"))
                .build());
        automation.setEnabled(true);
        automation.setActions(List.of());
        automation.setTriggerCount(0L);
        automation.setDeletedFlag(false);
        automation.setCreatedAt(Instant.now());
        automation.setUpdatedAt(Instant.now());
        automation.setCreatedBy(getTestUser().getPid());
        automation.setUpdatedBy(getTestUser().getPid());
        automationMapper.insertAutomation(automation);

        SlaConfigEntity sla = SlaConfigEntity.builder()
                .pid(UniqueIdGenerator.generate())
                .tenantId(getTestTenant().getId())
                .name("Impact SLA")
                .targetType("PROCESS")
                .targetKey("complaint_process")
                .deadlineMode("RULE")
                .deadlineValue(code)
                .ruleBinding(fieldRuleBinding(
                        "SLA_RULE",
                        "impact-sla",
                        "deadline",
                        "data.amount"))
                .enabled(true)
                .deletedFlag(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .createdBy(getTestUser().getId())
                .updatedBy(getTestUser().getId())
                .build();
        slaConfigMapper.insert(sla);

        DrtPolicyDefinitionEntity policy = new DrtPolicyDefinitionEntity();
        policy.setPid(UniqueIdGenerator.generate());
        policy.setTenantId(getTestTenant().getId());
        policy.setPolicyCode("policy_impact_" + suffix);
        policy.setPolicyName("Impact Policy");
        policy.setEventType("FORM_SUBMITTED");
        policy.setTargetType("FORM");
        policy.setTargetKey("complaint");
        policy.setEnabled(true);
        policy.setCreatedBy(getTestUser().getPid());
        policy.setUpdatedBy(getTestUser().getPid());
        policy.setCreatedAt(Instant.now());
        policy.setUpdatedAt(Instant.now());
        policyDefinitionMapper.insert(policy);

        DrtPolicyVersionEntity policyVersion = new DrtPolicyVersionEntity();
        policyVersion.setPid(UniqueIdGenerator.generate());
        policyVersion.setTenantId(getTestTenant().getId());
        policyVersion.setPolicyCode(policy.getPolicyCode());
        policyVersion.setVersion(1);
        policyVersion.setStatus("PUBLISHED");
        policyVersion.setPhase("AFTER_COMMIT");
        policyVersion.setMatchMode("COLLECT_ALL");
        policyVersion.setExecutionMode("ORDERED");
        policyVersion.setFailureStrategy("FAIL_FAST");
        policyVersion.setConflictStrategy("REJECT_ON_CONFLICT");
        policyVersion.setDedupStrategy("BY_IDEMPOTENCY_KEY");
        policyVersion.setRulesJson(json.readTree("""
                [{
                  "id": "rule-decision-ref",
                  "label": "Decision ref rule",
                  "decisionRef": "%s",
                  "conditions": [{
                    "type": "path",
                    "scope": "record",
                    "path": "data.amount"
                  }],
                  "actions": [{
                    "type": "NOTIFY",
                    "target": "ops",
                    "payload": { "decisionRef": "%s" }
                  }]
                }]
                """.formatted(code, code)));
        policyVersion.setContentHash("impact-hash-" + suffix);
        policyVersion.setPublishedBy(getTestUser().getPid());
        policyVersion.setPublishedAt(Instant.now());
        policyVersion.setCreatedAt(Instant.now());
        policyVersionMapper.insert(policyVersion);

        return new ImpactFixture(code, pid, automation.getPid(), sla.getPid(), policyVersion.getPid());
    }

    private RuleConsumerBinding fieldRuleBinding(
            String consumerType, String consumerCode, String consumerNodeId, String fieldPath) {
        ConditionNode.CompareNode condition = ConditionNode.CompareNode.of(
                new Operand.PathOperand(Scope.RECORD, fieldPath, DataType.DECIMAL),
                Operator.GT,
                new Operand.LiteralOperand(0, DataType.DECIMAL));
        return new RuleConsumerBinding(
                consumerType,
                consumerCode,
                consumerNodeId,
                RuleBindingKind.CONDITION,
                ConditionSpec.of(condition),
                null,
                true);
    }

    private void createDefinition(String code) throws Exception {
        mockMvc.perform(post("/api/decision/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "decisionCode", code,
                                "decisionName", "Impact IT",
                                "scopeType", "GOVERNANCE",
                                "ownerModule", "decision"))))
                .andExpect(status().isOk());
    }

    private String createValidatedVersion(String code) throws Exception {
        String draftBody = mockMvc.perform(
                        post("/api/decision/definitions/" + code + "/versions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "kind", "SIMPLE_CONDITION",
                                        "runtimeAdapter", "AST_EVALUATOR",
                                        "contentJson", json.readTree(AST)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pid = json.readTree(draftBody).path("data").path("pid").asText();
        mockMvc.perform(post("/api/decision/versions/" + pid + "/validate"))
                .andExpect(status().isOk());
        return pid;
    }

    private String createPublishedVersion(String code) throws Exception {
        String pid = createValidatedVersion(code);
        mockMvc.perform(post("/api/decision/versions/" + pid + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        return pid;
    }

    private DrtLogEntity createLog(
            Long tenantId,
            String traceId,
            String decisionCode,
            String status,
            Boolean matched,
            Long durationMs) {
        return createLog(tenantId, traceId, decisionCode, status, matched, durationMs, null);
    }

    private DrtLogEntity createLog(
            Long tenantId,
            String traceId,
            String decisionCode,
            String status,
            Boolean matched,
            Long durationMs,
            JsonNode outputSnapshot) {
        DrtLogEntity log = new DrtLogEntity();
        log.setPid(UniqueIdGenerator.generate());
        log.setTenantId(tenantId);
        log.setTraceId(traceId);
        log.setDecisionCode(decisionCode);
        log.setDecisionVersion(1);
        log.setSelectedVersion(1);
        log.setKind("SIMPLE_CONDITION");
        log.setRuntimeAdapter("AST_EVALUATOR");
        log.setCallerType("API");
        log.setMatched(matched);
        log.setStatus(status);
        log.setOutputSnapshot(outputSnapshot);
        log.setDurationMs(durationMs);
        log.setCreatedAt(Instant.now());
        logMapper.insert(log);
        return log;
    }

    private boolean hasReferenceWithMetadata(
            JsonNode references, String sourceType, String sourcePid, String metadataKey, String metadataValue) {
        if (references == null || !references.isArray()) {
            return false;
        }
        for (JsonNode reference : references) {
            if (sourceType.equals(reference.path("sourceType").asText())
                    && sourcePid.equals(reference.path("sourcePid").asText())
                    && metadataValue.equals(reference.path("metadata").path(metadataKey).asText())) {
                return true;
            }
        }
        return false;
    }

    private record ImpactFixture(
            String decisionCode,
            String publishedVersionPid,
            String automationPid,
            String slaPid,
            String policyVersionPid) {}

    private void grant(String code, String resourceType, String resourceCode, String action, String name) {
        Permission permission = permissionMapper.findByCode(code);
        if (permission == null) {
            permission = new Permission();
            permission.setPid(UniqueIdGenerator.generate());
            permission.setCode(code);
            permission.setName(name);
            permission.setResourceType(resourceType);
            permission.setResourceCode(resourceCode);
            permission.setAction(action);
            permission.setSource("manual");
            permission.setStatus("active");
            permission.setDeletedFlag(false);
            permission.setTenantId(getTestTenant().getId());
            permission.setCreatedAt(Instant.now());
            permission.setUpdatedAt(Instant.now());
            permissionMapper.insert(permission);
        }
        RolePermission rp = new RolePermission();
        rp.setPid(UniqueIdGenerator.generate());
        rp.setRoleId(getTestRole().getId());
        rp.setPermissionId(permission.getId());
        rp.setGrantType("grant");
        rp.setStatus("active");
        rp.setDeletedFlag(false);
        rp.setTenantId(getTestTenant().getId());
        rp.setCreatedAt(Instant.now());
        rp.setUpdatedAt(Instant.now());
        rolePermissionMapper.insert(rp);
    }

    private void revoke(String code) {
        Permission permission = permissionMapper.findByCode(code);
        if (permission == null) {
            return;
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, getTestRole().getId())
                .eq(RolePermission::getPermissionId, permission.getId()));
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }
}
