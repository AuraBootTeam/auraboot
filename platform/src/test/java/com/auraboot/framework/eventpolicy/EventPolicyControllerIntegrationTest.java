package com.auraboot.framework.eventpolicy;

import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.entity.DecisionUsageRefEntity;
import com.auraboot.framework.decision.mapper.DecisionUsageRefMapper;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP golden for the EventPolicy controller: drives the full lifecycle over MockMvc
 * (create definition → create draft version → validate → publish → run matched → run not-matched),
 * verifying routing, JSON request/response binding, and the {@code @RequirePermission} guard
 * (perms granted to the test role). Complements the service-layer real-stack IT.
 *
 * <p>Mirrors {@code DecisionRuntimeControllerIntegrationTest} exactly in structure.
 */
class EventPolicyControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private PermissionMapper permissionMapper;
    @Autowired private RolePermissionMapper rolePermissionMapper;
    @Autowired private UserPermissionService userPermissionService;
    @Autowired private DecisionUsageRefMapper usageRefMapper;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mockMvc;

    /**
     * Three-rule mockup s1 rules_json used in lifecycle and run tests.
     * R-101: record.data.priority EQ HIGH → NOTIFY
     * R-102: record.data.amount GT 10000 → START_PROCESS
     * R-103: record.data.customerLevel EQ VIP → CREATE_TASK
     * matchMode=COLLECT_ALL so all three fire when all conditions hold.
     */
    private static final String THREE_RULES_JSON = """
        [
          {
            "ruleCode": "R-101",
            "ruleName": "High priority notification",
            "priority": 1,
            "enabled": true,
            "condition": {
              "type": "compare",
              "left": { "type": "path", "scope": "record", "path": "data.priority", "dataType": "string" },
              "operator": "EQ",
              "right": { "type": "literal", "value": "HIGH", "dataType": "string" }
            },
            "actions": [
              {
                "type": "NOTIFY",
                "target": "ROLE:support_manager",
                "order": 1,
                "payload": { "template": "high_priority_alert" },
                "idempotencyKeyTemplate": "${record.data.recordId}:${rule.ruleCode}:${action.type}"
              }
            ]
          },
          {
            "ruleCode": "R-102",
            "ruleName": "Large amount process",
            "priority": 2,
            "enabled": true,
            "condition": {
              "type": "compare",
              "left": { "type": "path", "scope": "record", "path": "data.amount", "dataType": "decimal" },
              "operator": "GT",
              "right": { "type": "literal", "value": 10000, "dataType": "decimal" }
            },
            "actions": [
              {
                "type": "START_PROCESS",
                "target": "BPM:complaint_approval",
                "order": 1,
                "payload": { "processKey": "complaint_approval" },
                "idempotencyKeyTemplate": "${record.data.recordId}:${rule.ruleCode}:${action.type}"
              }
            ]
          },
          {
            "ruleCode": "R-103",
            "ruleName": "VIP customer task",
            "priority": 3,
            "enabled": true,
            "condition": {
              "type": "compare",
              "left": { "type": "path", "scope": "record", "path": "data.customerLevel", "dataType": "string" },
              "operator": "EQ",
              "right": { "type": "literal", "value": "VIP", "dataType": "string" }
            },
            "actions": [
              {
                "type": "CREATE_TASK",
                "target": "ASSIGNEE:account_manager",
                "order": 1,
                "payload": { "taskTemplate": "vip_follow_up" },
                "idempotencyKeyTemplate": "${record.data.recordId}:${rule.ruleCode}:${action.type}"
              }
            ]
          }
        ]
        """;

    @BeforeEach
    void setupAuthAndMockMvc() {
        grant("decision.policy.read", "decision", "policy", "read", "Event Policy Read");
        grant("decision.policy.manage", "decision", "policy", "manage", "Event Policy Manage");
        grant("decision.policy.publish", "decision", "policy", "publish", "Event Policy Publish");
        grant("decision.policy.run", "decision", "policy", "run", "Event Policy Run");
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
    void httpListDefinitions_supportsConsoleCatalogueAndOptionalFilters() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String codeA = "ep_console_a_" + suffix;
        String codeB = "ep_console_b_" + suffix;
        String eventTypeA = "FORM_SUBMITTED_" + suffix;
        String eventTypeB = "TASK_ESCALATED_" + suffix;

        mockMvc.perform(post("/api/event-policy/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "policyCode", codeA,
                                "policyName", "Console Primary " + suffix,
                                "eventType", eventTypeA,
                                "targetType", "FORM",
                                "targetKey", "complaint-" + suffix))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/event-policy/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "policyCode", codeB,
                                "policyName", "Console Secondary " + suffix,
                                "eventType", eventTypeB,
                                "targetType", "TASK",
                                "targetKey", "sla-" + suffix))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/event-policy/definitions/" + codeA + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "phase", "AFTER_COMMIT",
                                "matchMode", "COLLECT_ALL",
                                "rulesJson", json.readTree(THREE_RULES_JSON)))))
                .andExpect(status().isOk());

        String allBody = mockMvc.perform(get("/api/event-policy/definitions"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode allRows = json.readTree(allBody).path("data");
        assertTrue(containsPolicyCode(allRows, codeA));
        assertTrue(containsPolicyCode(allRows, codeB));
        JsonNode rowA = findPolicyCode(allRows, codeA);
        assertTrue(rowA.path("version").asInt() == 1);
        assertTrue("DRAFT".equals(rowA.path("status").asText()));
        assertTrue("AFTER_COMMIT".equals(rowA.path("phase").asText()));
        assertTrue("COLLECT_ALL".equals(rowA.path("matchMode").asText()));

        String statusBody = mockMvc.perform(get("/api/event-policy/definitions")
                        .param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode statusRows = json.readTree(statusBody).path("data");
        assertTrue(containsPolicyCode(statusRows, codeA));
        assertFalse(containsPolicyCode(statusRows, codeB));

        String eventTypeBody = mockMvc.perform(get("/api/event-policy/definitions")
                        .param("eventType", eventTypeA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode eventTypeRows = json.readTree(eventTypeBody).path("data");
        assertTrue(containsPolicyCode(eventTypeRows, codeA));
        assertFalse(containsPolicyCode(eventTypeRows, codeB));

        String keywordBody = mockMvc.perform(get("/api/event-policy/definitions")
                        .param("keyword", "Console Primary " + suffix))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode keywordRows = json.readTree(keywordBody).path("data");
        assertTrue(containsPolicyCode(keywordRows, codeA));
        assertFalse(containsPolicyCode(keywordRows, codeB));
    }

    @Test
    void httpDefinitionCommands_toggleEnabledAndCopyLatestVersionAsDraft() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String sourceCode = "ep_cmd_source_" + suffix;
        String copyCode = "ep_cmd_copy_" + suffix;

        mockMvc.perform(post("/api/event-policy/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "policyCode", sourceCode,
                                "policyName", "Command Source " + suffix,
                                "eventType", "FORM_SUBMITTED",
                                "targetType", "FORM",
                                "targetKey", "complaint-" + suffix))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));

        mockMvc.perform(post("/api/event-policy/definitions/" + sourceCode + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "phase", "AFTER_COMMIT",
                                "matchMode", "COLLECT_ALL",
                                "executionMode", "ORDERED",
                                "failureStrategy", "FAIL_FAST",
                                "conflictStrategy", "REJECT_ON_CONFLICT",
                                "dedupStrategy", "BY_IDEMPOTENCY_KEY",
                                "rulesJson", json.readTree(THREE_RULES_JSON)))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/event-policy/definitions/" + sourceCode + "/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyCode").value(sourceCode))
                .andExpect(jsonPath("$.data.enabled").value(false));

        String listBody = mockMvc.perform(get("/api/event-policy/definitions")
                        .param("keyword", sourceCode))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode sourceRow = findPolicyCode(json.readTree(listBody).path("data"), sourceCode);
        assertFalse(sourceRow.path("enabled").asBoolean());

        mockMvc.perform(post("/api/event-policy/definitions/" + sourceCode + "/copy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "policyCode", copyCode,
                                "policyName", "Command Copy " + suffix))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyCode").value(copyCode))
                .andExpect(jsonPath("$.data.policyName").value("Command Copy " + suffix))
                .andExpect(jsonPath("$.data.enabled").value(true));

        mockMvc.perform(get("/api/event-policy/definitions/" + copyCode + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].policyCode").value(copyCode))
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].matchMode").value("COLLECT_ALL"));
    }

    @Test
    void httpLifecycle_validateAndPublishRefreshDecisionUsageIndexForPolicySource() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String policyCode = "ep_usage_hook_" + suffix;
        String decisionCode = "decision_usage_hook_" + suffix;
        JsonNode rulesJson = json.readTree("""
                [{
                  "ruleCode": "R-DECISION-REF",
                  "ruleName": "Decision reference",
                  "priority": 1,
                  "enabled": true,
                  "decisionRef": "%s",
                  "condition": {
                    "type": "compare",
                    "left": { "type": "path", "scope": "record", "path": "data.priority", "dataType": "string" },
                    "operator": "EQ",
                    "right": { "type": "literal", "value": "HIGH", "dataType": "string" }
                  },
                  "actions": []
                }]
                """.formatted(decisionCode));

        mockMvc.perform(post("/api/event-policy/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "policyCode", policyCode,
                                "policyName", "Usage Hook " + suffix,
                                "eventType", "FORM_SUBMITTED",
                                "targetType", "FORM",
                                "targetKey", "complaint-" + suffix))))
                .andExpect(status().isOk());

        String draftBody = mockMvc.perform(post("/api/event-policy/definitions/" + policyCode + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "phase", "AFTER_COMMIT",
                                "matchMode", "COLLECT_ALL",
                                "rulesJson", rulesJson))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String versionPid = json.readTree(draftBody).path("data").path("pid").asText();

        mockMvc.perform(post("/api/event-policy/versions/" + versionPid + "/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VALIDATED"));

        applyTestMetaContext();
        List<DecisionUsageRefEntity> validatedRefs =
                usageRefMapper.findIncomingDecisionRefs(getTestTenant().getId(), decisionCode);
        assertTrue(validatedRefs.stream().anyMatch(ref ->
                "EVENT_POLICY".equals(ref.getSourceType()) && versionPid.equals(ref.getSourcePid())));

        mockMvc.perform(post("/api/event-policy/versions/" + versionPid + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        applyTestMetaContext();
        List<DecisionUsageRefEntity> publishedRefs =
                usageRefMapper.findIncomingDecisionRefs(getTestTenant().getId(), decisionCode);
        assertTrue(publishedRefs.stream().anyMatch(ref ->
                "EVENT_POLICY".equals(ref.getSourceType()) && versionPid.equals(ref.getSourcePid())
                        && ref.getMetadataJson().path("status").asText().equals("PUBLISHED")));
    }

    @Test
    void httpLifecycle_create_draftVersion_validate_publish_run_allThreeRulesMatch() throws Exception {
        String code = "ep_http_" + System.nanoTime();

        // 1. create definition (eventType=FORM_SUBMITTED, targetType=FORM, targetKey=complaint)
        mockMvc.perform(post("/api/event-policy/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "policyCode", code,
                                "policyName", "HTTP IT Policy",
                                "eventType", "FORM_SUBMITTED",
                                "targetType", "FORM",
                                "targetKey", "complaint"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyCode").value(code))
                .andExpect(jsonPath("$.data.eventType").value("FORM_SUBMITTED"))
                .andExpect(jsonPath("$.data.targetType").value("FORM"))
                .andExpect(jsonPath("$.data.targetKey").value("complaint"));

        // 2. GET definition by code
        mockMvc.perform(get("/api/event-policy/definitions/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyCode").value(code));

        // 3. create draft version → capture pid
        String draftBody = mockMvc.perform(
                        post("/api/event-policy/definitions/" + code + "/versions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "phase", "AFTER_COMMIT",
                                        "matchMode", "COLLECT_ALL",
                                        "executionMode", "ORDERED",
                                        "failureStrategy", "FAIL_FAST",
                                        "conflictStrategy", "REJECT_ON_CONFLICT",
                                        "dedupStrategy", "BY_IDEMPOTENCY_KEY",
                                        "rulesJson", json.readTree(THREE_RULES_JSON)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.matchMode").value("COLLECT_ALL"))
                .andReturn().getResponse().getContentAsString();
        String pid = json.readTree(draftBody).path("data").path("pid").asText();

        // 4. list versions → should contain the draft
        mockMvc.perform(get("/api/event-policy/definitions/" + code + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].policyCode").value(code))
                .andExpect(jsonPath("$.data[0].version").value(1));

        // 5. validate version → VALIDATED
        mockMvc.perform(post("/api/event-policy/versions/" + pid + "/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VALIDATED"));

        // 6. publish version → PUBLISHED
        mockMvc.perform(post("/api/event-policy/versions/" + pid + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.contentHash").isNotEmpty());

        // 7. POST /run with all three conditions satisfied → MATCHED + 3 rules + 3 action plans
        mockMvc.perform(post("/api/event-policy/run").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "eventType", "FORM_SUBMITTED",
                                "targetType", "FORM",
                                "targetKey", "complaint",
                                "context", Map.of("record", Map.of(
                                        "entityCode", "complaint",
                                        "recordId", "CMP-1",
                                        "data", Map.of(
                                                "priority", "HIGH",
                                                "amount", 20000,
                                                "customerLevel", "VIP")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MATCHED"))
                .andExpect(jsonPath("$.data.matchedRuleCodes").isArray())
                .andExpect(jsonPath("$.data.matchedRuleCodes.length()").value(3))
                .andExpect(jsonPath("$.data.actionPlans.length()").value(3));
    }

    @Test
    void httpRun_notMatchedCase_smallAmountNormalPriorityNonVip() throws Exception {
        String code = "ep_http_nm_" + System.nanoTime();

        // Setup: create + publish a policy
        mockMvc.perform(post("/api/event-policy/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "policyCode", code, "policyName", "NM Test Policy",
                                "eventType", "FORM_SUBMITTED", "targetType", "FORM", "targetKey", "complaint"))))
                .andExpect(status().isOk());

        String draftBody = mockMvc.perform(
                        post("/api/event-policy/definitions/" + code + "/versions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "phase", "AFTER_COMMIT",
                                        "matchMode", "COLLECT_ALL",
                                        "rulesJson", json.readTree(THREE_RULES_JSON)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pid = json.readTree(draftBody).path("data").path("pid").asText();

        mockMvc.perform(post("/api/event-policy/versions/" + pid + "/validate"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/event-policy/versions/" + pid + "/publish"))
                .andExpect(status().isOk());

        // POST /run with none of the conditions satisfied → NOT_MATCHED
        mockMvc.perform(post("/api/event-policy/run").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "eventType", "FORM_SUBMITTED",
                                "targetType", "FORM",
                                "targetKey", "complaint",
                                "context", Map.of("record", Map.of(
                                        "data", Map.of(
                                                "priority", "LOW",
                                                "amount", 500,
                                                "customerLevel", "REGULAR")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_MATCHED"))
                .andExpect(jsonPath("$.data.matchedRuleCodes").isEmpty())
                .andExpect(jsonPath("$.data.actionPlans").isEmpty());
    }

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

    private boolean containsPolicyCode(JsonNode rows, String policyCode) {
        return !findPolicyCode(rows, policyCode).isMissingNode();
    }

    private JsonNode findPolicyCode(JsonNode rows, String policyCode) {
        if (!rows.isArray()) {
            return MissingNode.getInstance();
        }
        for (JsonNode row : rows) {
            if (policyCode.equals(row.path("policyCode").asText())) {
                return row;
            }
        }
        return MissingNode.getInstance();
    }
}
