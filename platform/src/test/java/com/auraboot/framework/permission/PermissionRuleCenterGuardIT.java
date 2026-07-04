package com.auraboot.framework.permission;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Permission Rule Center Guard")
class PermissionRuleCenterGuardIT extends BaseIntegrationTest {

    @Autowired private DrtDefinitionService decisionDefinitionService;
    @Autowired private DecisionVersionService decisionVersionService;
    @Autowired private PermissionService permissionService;
    @Autowired private RolePermissionMapper rolePermissionMapper;
    @Autowired private UserPermissionService userPermissionService;
    @Autowired private PermissionEvaluator permissionEvaluator;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void permissionGrantUsesRuleCenterDecisionBindingAtRuntime() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String decisionCode = "perm_amount_guard_" + suffix;
        String resource = "perm_rule_guard_" + suffix;
        String action = "approve";
        publishAmountDecision(decisionCode);
        PermissionDTO permission = createPermission(resource, action);
        bindGrantWithRuleCenterGuard(permission.getId(), permission.getCode(), decisionCode);

        PermissionResult allowed = permissionEvaluator.canOperate(
                getTestTenantMember().getId(), resource, action, Map.of("amount", 10000));
        PermissionResult denied = permissionEvaluator.canOperate(
                getTestTenantMember().getId(), resource, action, Map.of("amount", 80000));

        assertThat(allowed.granted())
                .as("amount 10000 should satisfy the permission Rule Center decision: %s", allowed.reason())
                .isTrue();
        assertThat(denied.granted())
                .as("amount 80000 should fail the permission Rule Center decision: %s", denied.reason())
                .isFalse();
        assertThat(denied.reason()).contains(decisionCode);
    }

    private void publishAmountDecision(String decisionCode) throws Exception {
        DrtDefinitionCreateRequest def = new DrtDefinitionCreateRequest();
        def.setDecisionCode(decisionCode);
        def.setDecisionName("Permission amount guard");
        def.setScopeType("PERMISSION");
        def.setOwnerModule("decision");
        decisionDefinitionService.create(def);

        JsonNode ast = mapper.readTree("""
            {
              "type": "compare",
              "left": { "type": "path", "scope": "record", "path": "data.amount", "dataType": "decimal" },
              "operator": "LTE",
              "right": { "type": "literal", "value": 50000, "dataType": "decimal" }
            }
            """);
        DrtVersionCreateRequest version = new DrtVersionCreateRequest();
        version.setKind("SIMPLE_CONDITION");
        version.setRuntimeAdapter("AST_EVALUATOR");
        version.setContentJson(ast);
        DrtVersionDTO draft = decisionVersionService.createDraft(decisionCode, version);
        decisionVersionService.validate(draft.getPid());
        decisionVersionService.publish(draft.getPid());
    }

    private PermissionDTO createPermission(String resource, String action) {
        PermissionCreateRequest req = new PermissionCreateRequest();
        req.setCode(resource + ":" + action);
        req.setName("Rule Center Guard " + resource);
        req.setResourceType("model");
        req.setResourceCode(resource);
        req.setAction(action);
        req.setSource("integration_test");
        return permissionService.create(req);
    }

    private void bindGrantWithRuleCenterGuard(Long permissionId, String permissionCode, String decisionCode) throws Exception {
        RolePermission binding = new RolePermission();
        binding.setPid(UniqueIdGenerator.generate());
        binding.setTenantId(getTestTenant().getId());
        binding.setRoleId(getTestRole().getId());
        binding.setPermissionId(permissionId);
        binding.setGrantType("grant");
        binding.setPriority(100);
        binding.setStatus("active");
        binding.setDeletedFlag(false);
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());
        rolePermissionMapper.insert(binding);

        JsonNode conditions = mapper.readTree(("""
            {
              "dynamicAbac": {
                "expectedMatched": true,
                "ruleBinding": {
                  "consumerType": "PERMISSION",
                  "consumerCode": "%s",
                  "consumerNodeId": "dynamicAbac",
                  "bindingKind": "DECISION_REF",
                  "enabled": true,
                  "decisionBinding": {
                    "decisionCode": "%s",
                    "versionPolicy": "LATEST_PUBLISHED",
                    "inputMappings": [
                      {
                        "input": "amount",
                        "source": { "kind": "FIELD", "scope": "record", "path": "data.amount" }
                      }
                    ],
                    "fallbackPolicy": { "mode": "FAIL_CLOSED" },
                    "traceMode": "ALWAYS",
                    "enabled": true
                  }
                }
              }
            }
            """).formatted(permissionCode, decisionCode));
        rolePermissionMapper.updateConditionsById(binding.getId(), mapper.writeValueAsString(conditions));
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }
}
