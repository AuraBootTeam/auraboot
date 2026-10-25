package com.auraboot.framework.permission;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MetaModelService metaModelService;
    @Autowired private MetaFieldService metaFieldService;
    @Autowired private UserService userService;

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

    @Test
    void permissionRuleCenterDecisionBindingWritesApplicantReferenceFactMetadataAtRuntime() throws Exception {
        String suffix = Long.toString(Math.abs(System.nanoTime()), 36);
        String modelCode = "perm_applicant_model_" + suffix;
        String fieldCode = "applicant_ref_" + suffix;
        String decisionCode = "perm_applicant_decision_" + suffix;
        String action = "read";
        String applicantPid = getTestUser().getPid();

        saveUserReferenceModel(modelCode, fieldCode);
        UserSearchDTO applicant = userService.findInTenantByPid(getTestTenant().getId(), applicantPid);
        assertThat(applicant).isNotNull();
        assertThat(applicant.getDisplayName()).isNotBlank();

        publishApplicantDecision(decisionCode, fieldCode, applicantPid);
        PermissionDTO permission = createPermission(modelCode, action);
        bindGrantWithApplicantRuleCenterGuard(permission.getId(), permission.getCode(), decisionCode, fieldCode);

        PermissionResult allowed = permissionEvaluator.canOperate(
                getTestTenantMember().getId(),
                modelCode,
                action,
                Map.of("pid", "PERM-APPLICANT-" + suffix, fieldCode, applicantPid));

        assertThat(allowed.granted())
                .as("applicant reference should satisfy the Permission Rule Center decision: %s", allowed.reason())
                .isTrue();

        String traceId = allowed.steps().stream()
                .map(EvaluationStep::details)
                .map(details -> details.get("ruleTraceId"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .findFirst()
                .orElseThrow();

        Map<String, Object> metadata = jdbcTemplate.queryForMap(
                """
                select
                  caller_type,
                  caller_ref,
                  trace_snapshot->'factMetadata'->?->>'label' as label,
                  trace_snapshot->'factMetadata'->?->>'modelCode' as model_code,
                  trace_snapshot->'factMetadata'->?->>'dataType' as data_type,
                  trace_snapshot->'factMetadata'->?->'valueLabels'->>? as value_label
                from ab_drt_log
                where tenant_id = ? and trace_id = ?
                """,
                "record.data." + fieldCode,
                "record.data." + fieldCode,
                "record.data." + fieldCode,
                "record.data." + fieldCode,
                applicantPid,
                getTestTenant().getId(),
                traceId);

        assertThat(metadata.get("caller_type")).isEqualTo("PERMISSION");
        assertThat(metadata.get("caller_ref")).isEqualTo(permission.getCode());
        assertThat(metadata.get("label")).isEqualTo("申请人");
        assertThat(metadata.get("model_code")).isEqualTo(modelCode);
        assertThat(metadata.get("data_type")).isEqualTo("reference");
        assertThat(metadata.get("value_label")).isEqualTo(applicant.getDisplayName());
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

    private void publishApplicantDecision(String decisionCode, String fieldCode, String userPid) {
        DrtDefinitionCreateRequest def = new DrtDefinitionCreateRequest();
        def.setDecisionCode(decisionCode);
        def.setDecisionName("Permission applicant guard");
        def.setScopeType("PERMISSION");
        def.setOwnerModule("decision");
        decisionDefinitionService.create(def);

        DrtVersionCreateRequest version = new DrtVersionCreateRequest();
        version.setKind("SIMPLE_CONDITION");
        version.setRuntimeAdapter("AST_EVALUATOR");
        version.setContentJson(mapper.valueToTree(Map.of(
                "type", "compare",
                "left", Map.of(
                        "type", "path",
                        "scope", "record",
                        "path", "data." + fieldCode,
                        "dataType", "user"),
                "operator", "EQ",
                "right", Map.of(
                        "type", "literal",
                        "value", userPid,
                        "dataType", "user"))));

        DrtVersionDTO draft = decisionVersionService.createDraft(decisionCode, version);
        DecisionValidateResult validation = decisionVersionService.validate(draft.getPid());
        assertThat(validation.valid()).isTrue();
        assertThat(validation.fieldRefs()).contains("record.data." + fieldCode);
        DrtVersionDTO published = decisionVersionService.publish(draft.getPid());
        assertThat(published.getStatus()).isEqualTo(VersionStatus.PUBLISHED.name());
    }

    private void saveUserReferenceModel(String modelCode, String fieldCode) {
        MetaModelCreateRequest modelRequest = new MetaModelCreateRequest();
        modelRequest.setCode(modelCode);
        modelRequest.setDisplayName("Permission Reference Metadata " + modelCode);
        modelRequest.setModelType("entity");
        modelRequest.setSourceType("physical");
        modelRequest.setPrimaryKey("pid");

        MetaModelDTO model = metaModelService.create(modelRequest);
        assertThat(model.getPid()).isNotBlank();

        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("reference");
        fieldRequest.setRefTarget(Map.of(
                "targetEntity", "sys_user",
                "displayField", "displayName",
                "valueField", "pid"));
        fieldRequest.setExtension(Map.of("displayName", "申请人"));
        fieldRequest.setAutoPublish(true);

        MetaFieldDTO field = metaFieldService.create(fieldRequest);
        assertThat(field.getPid()).isNotBlank();
        assertThat(field.getRefTarget()).containsEntry("targetEntity", "sys_user");
        metaModelService.bindFieldToModel(
                model.getId(),
                field.getId(),
                1,
                false,
                true,
                true,
                null,
                null,
                null,
                null);

        MetaModelDTO published = metaModelService.publish(
                model.getPid(),
                "PermissionRuleCenterGuardIT reference fact metadata fixture",
                true,
                "Permission trace reference fact metadata fixture");
        assertThat(published.getStatus()).isEqualToIgnoringCase("published");
    }

    private PermissionDTO createPermission(String resource, String action) {
        PermissionCreateRequest req = new PermissionCreateRequest();
        req.setCode(resource + ":" + action);
        req.setName("Rule Center Guard " + resource);
        req.setResourceType("model");
        req.setResourceCode(resource);
        req.setAction(action);
        req.setSource("integration_test");
        PermissionDTO created = permissionService.create(req);
        userPermissionService.evictPermissionDefinitions(getTestTenant().getId());
        return created;
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
        binding.setConditions(mapper.convertValue(conditions, Object.class));
        rolePermissionMapper.updateById(binding);
        userPermissionService.evictRoleUsers(getTestTenant().getId(), getTestRole().getId());
    }

    private void bindGrantWithApplicantRuleCenterGuard(Long permissionId,
                                                       String permissionCode,
                                                       String decisionCode,
                                                       String fieldCode) throws Exception {
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
                        "input": "%s",
                        "source": { "kind": "FIELD", "scope": "record", "path": "data.%s" }
                      }
                    ],
                    "fallbackPolicy": { "mode": "FAIL_CLOSED" },
                    "traceMode": "ALWAYS",
                    "enabled": true
                  }
                }
              }
            }
            """).formatted(permissionCode, decisionCode, fieldCode, fieldCode));
        binding.setConditions(mapper.convertValue(conditions, Object.class));
        rolePermissionMapper.updateById(binding);
        userPermissionService.evictRoleUsers(getTestTenant().getId(), getTestRole().getId());
    }
}
