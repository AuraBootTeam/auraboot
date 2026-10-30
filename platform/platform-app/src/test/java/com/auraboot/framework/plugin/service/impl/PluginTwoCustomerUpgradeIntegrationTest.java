package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.decision.dto.*;
import com.auraboot.framework.decision.model.VersionBinding;
import com.auraboot.framework.decision.service.*;
import com.auraboot.framework.meta.dto.PageSchemaUpdateRequest;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Public import service + distributed lock + real transactions and decision execution. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PluginTwoCustomerUpgradeIntegrationTest extends BaseIntegrationTest {
    @Autowired PluginImportService imports;
    @Autowired PageSchemaService pages;
    @Autowired PluginResourceTracker tracker;
    @Autowired DecisionVersionService versions;
    @Autowired DecisionEvaluationService evaluation;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConditionFragmentService fragments;
    @Autowired com.auraboot.framework.eventpolicy.service.EventPolicyDefinitionService policies;
    @Autowired com.auraboot.framework.eventpolicy.service.EventPolicyVersionService policyVersions;
    @Autowired com.auraboot.framework.automation.service.AutomationService automations;
    @Autowired com.auraboot.framework.meta.service.DynamicDataService data;
    @Autowired com.auraboot.framework.tenant.service.TenantMemberService members;
    @Autowired com.auraboot.framework.rbac.service.RoleService roles;
    @Autowired com.auraboot.framework.rbac.service.UserRoleService userRoles;
    @Autowired com.auraboot.framework.permission.service.DataScopeService scopes;
    @Autowired com.auraboot.framework.permission.service.PermissionService permissions;
    @Autowired com.auraboot.framework.rbac.mapper.RolePermissionMapper rolePermissions;

    @Test
    void twoCustomersKeepCustomizationAbsorbDefaultsAndContinueAfterFailedUpgrade() throws Exception {
        String code = "upgrade_" + UniqueIdGenerator.generate().toLowerCase();
        long originalTenant = MetaContext.getCurrentTenantId();
        Long originalMember = MetaContext.getCurrentMemberId();
        long a = System.nanoTime();
        long b = a + 1;
        Long user = MetaContext.getCurrentUserId();
        String userPid = MetaContext.getCurrentUserPid();
        jdbc.update("INSERT INTO ab_tenant(id,pid,name) VALUES (?,?,?)", a, UniqueIdGenerator.generate(), "Customer A " + code);
        MetaContext.setContext(a, user, userPid, "Customer A reviewer");
        jdbc.update("INSERT INTO ab_tenant(id,pid,name) VALUES (?,?,?)", b, UniqueIdGenerator.generate(), "Customer B " + code);
        try {
            var firstA = install(code, "1.0.0", 100000);
            configureAccess(a, user, code);
            var recordA = data.create(code, Map.of(code + "_name", "Customer A supply-chain delivery", code + "_amount", 170000));
            String recordAPid = recordA.get("pid").toString();
            var pageA = pages.findByPageKey(code);
            var edit = new PageSchemaUpdateRequest();
            edit.setName("Customer A delivery workspace");
            pages.update(pageA.getPid(), edit);
            tracker.markAsUserModified(ResourceType.PAGE, code);
            publishCustomerRule(code, 200000);

            MetaContext.setContext(b, user, userPid, "Customer B reviewer");
            var memberB = members.findByTenantIdAndUserId(b, user);
            if (memberB != null) MetaContext.setMemberId(memberB.getId());
            var firstB = install(code, "1.0.0", 100000);
            configureAccess(b, user, code);
            var recordB = data.create(code, Map.of(code + "_name", "Customer B equipment delivery", code + "_amount", 170000));
            String recordBPid = recordB.get("pid").toString();
            assertThat(recordBPid).isNotEqualTo(recordAPid);
            assertThatThrownBy(() -> data.getById(code, recordAPid))
                    .isInstanceOf(com.auraboot.framework.meta.exception.MetaRecordNotFoundException.class);
            assertThat(firstA.getPluginPid()).isNotEqualTo(firstB.getPluginPid());
            assertThat(pages.findByPageKey(code).getPid()).isNotEqualTo(pageA.getPid());
            assertThat(matches(code, 170000)).isTrue();
            install(code, "1.1.0", 150000);
            assertThat(pages.findByPageKey(code).getName()).isEqualTo("Delivery workspace 1.1.0");
            assertThat(latestThreshold(code)).isEqualTo(150000);
            int beforeRepeat = versions.listByCode(code).size();
            install(code, "1.1.0", 150000);
            assertThat(versions.listByCode(code)).hasSize(beforeRepeat);

            MetaContext.setContext(a, user, userPid, "Customer A reviewer");
            MetaContext.setMemberId(members.findByTenantIdAndUserId(a, user).getId());
            var upgradeA = install(code, "1.1.0", 150000);
            assertThat(pages.findByPageKey(code).getName()).isEqualTo("Customer A delivery workspace");
            assertThat(latestThreshold(code)).isEqualTo(200000);
            assertThat(matches(code, 170000)).isFalse();
            assertThat(upgradeA.getWarnings()).anyMatch(w -> w.contains(code) && w.contains("customer changes"));
            install(code, "1.1.0", 150000);
            assertThat(latestThreshold(code)).isEqualTo(200000);

            MetaContext.setContext(b, user, userPid, "Customer B reviewer");
            MetaContext.setMemberId(members.findByTenantIdAndUserId(b, user).getId());
            var bad = manifest(code, "1.2.0", 180000);
            bad.getDecisionDefinitions().getFirst().setRuntimeAdapter("INVALID_ADAPTER");
            int beforeFailure = versions.listByCode(code).size();
            assertThatThrownBy(() -> imports.executeFromManifest(bad, safe())).hasMessageContaining("Import failed");
            assertThat(pages.findByPageKey(code).getName()).isEqualTo("Delivery workspace 1.1.0");
            assertThat(versions.listByCode(code)).hasSize(beforeFailure);
            assertThat(latestThreshold(code)).isEqualTo(150000);
            assertThat(matches(code, 170000)).isTrue();
            assertThat(data.getById(code, recordBPid)).containsEntry(code + "_name", "Customer B equipment delivery");
            data.update(code, recordBPid, Map.of(code + "_amount", 175000));
            assertThat(new java.math.BigDecimal(data.getById(code, recordBPid).get(code + "_amount").toString())).isEqualByComparingTo("175000");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_plugin_import_history WHERE tenant_id=? AND plugin_id=? AND status='failed'", Integer.class, b, code)).isPositive();
            install(code, "1.2.0", 180000);
            assertThat(pages.findByPageKey(code).getName()).isEqualTo("Delivery workspace 1.2.0");
            assertThat(latestThreshold(code)).isEqualTo(180000);
            assertThat(matches(code, 170000)).isFalse();
        } finally {
            MetaContext.setContext(originalTenant, user, userPid, "Integration reviewer");
            MetaContext.setMemberId(originalMember);
        }
    }

    @Test
    void moduleDefinitionsAbsorbDefaultsButPreserveNewDraftsMetadataAndRenames() throws Exception {
        String code = "module_" + UniqueIdGenerator.generate().toLowerCase();
        imports.executeFromManifest(moduleManifest(code, "1.0.0", 100000), safe());
        imports.executeFromManifest(moduleManifest(code, "1.1.0", 150000), safe());
        assertThat(fragments.findByCode(code).getFragmentName()).isEqualTo("Condition 1.1.0");
        assertThat(automations.findPluginSeed(code, code).getDescription()).isEqualTo("Automation 1.1.0");
        assertThat(policyVersions.listByCode(code)).anyMatch(v -> v.getRulesJson().get(0).path("ruleName").asText().equals("Policy 1.1.0"));
        int fragmentCount = fragments.listVersions(code).size();
        int policyCount = policyVersions.listByCode(code).size();
        imports.executeFromManifest(moduleManifest(code, "1.1.0", 150000), safe());
        assertThat(fragments.listVersions(code)).hasSize(fragmentCount);
        assertThat(policyVersions.listByCode(code)).hasSize(policyCount);

        var draft = new ConditionFragmentVersionCreateRequest();
        draft.setFragmentName("Customer draft");draft.setScopeType("GLOBAL");draft.setConditionSpec(json.createObjectNode().set("root", ast(200000)));
        fragments.createVersion(code, draft);
        var automation = automations.findPluginSeed(code, code);
        var edit = new com.auraboot.framework.automation.dto.AutomationUpdateRequest();
        edit.setName("Customer renamed automation");edit.setDescription("Customer automation");
        automations.update(automation.getPid(), edit);
        policies.setEnabled(code, true);
        publishCustomerRule(code, 200000);
        var result = imports.executeFromManifest(moduleManifest(code, "1.2.0", 180000), safe());
        assertThat(result.getWarnings()).hasSize(4);
        assertThat(fragments.listVersions(code)).hasSize(fragmentCount + 1);
        assertThat(fragments.findByCode(code).getFragmentName()).isEqualTo("Customer draft");
        assertThat(automations.findPluginSeed(code, code).getName()).isEqualTo("Customer renamed automation");
        assertThat(automations.getByModelCode(code)).hasSize(1);
        assertThat(policies.findByCode(code).getEnabled()).isTrue();
        assertThat(policyVersions.listByCode(code)).hasSize(policyCount);
        assertThat(latestThreshold(code)).isEqualTo(200000);
        var repeated = imports.executeFromManifest(moduleManifest(code, "1.2.0", 180000), safe());
        assertThat(repeated.getWarnings()).hasSize(4);
        assertThat(latestThreshold(code)).isEqualTo(200000);
    }

    private PluginManifestExtended moduleManifest(String code, String version, int threshold) throws Exception {
        var manifest = manifest(code, version, threshold);
        manifest.setConditionFragments(List.of(ConditionFragmentSeedDTO.builder().fragmentCode(code)
                .fragmentName("Condition " + version).scopeType("GLOBAL").enabled(true)
                .conditionSpec(json.createObjectNode().set("root", ast(threshold))).publish(true).build()));
        manifest.setEventPolicies(List.of(EventPolicySeedDTO.builder().policyCode(code).policyName("Delivery policy")
                .eventType("upgraded").targetType("MODEL").targetKey(code).enabled(false).publish(true)
                .rulesJson(json.createArrayNode().add(json.createObjectNode().put("ruleCode", "delivery")
                        .put("ruleName", "Policy " + version).put("enabled", false)
                        .set("condition", ast(threshold)))).build()));
        manifest.setAutomations(List.of(AutomationDefinitionDTO.builder().automationKey(code).name("Delivery automation")
                .description("Automation " + version).modelCode(code).triggerType("on_record_update").enabled(false)
                .actions(List.of(com.auraboot.framework.automation.entity.AutomationAction.builder().type("delay")
                        .config(Map.of("seconds", threshold / 100000)).sequence(0).build())).build()));
        return manifest;
    }

    private void configureAccess(long tenant, Long user, String code) {
        var member = members.addMember(user, tenant, "active");
        var role = new com.auraboot.framework.rbac.entity.Role();
        role.setPid(UniqueIdGenerator.generate());role.setTenantId(tenant);role.setName("Delivery reviewer");
        role.setCode("upgrade_reviewer");role.setType("custom");role.setScopeType("tenant");role.setStatus("active");
        role.setDeletedFlag(false);role.setIsDefault(false);role.setIsSystem(false);role.setPriority(100);
        role = roles.createRole(role);
        assertThat(userRoles.assignRolesToMember(member.getId(), List.of(role.getId()), tenant, null)).isTrue();
        for (String action : List.of("read", "create", "update")) {
            var permissionRequest = new com.auraboot.framework.permission.dto.PermissionCreateRequest();
            permissionRequest.setCode(code + ":" + action);permissionRequest.setResourceType("model");
            permissionRequest.setResourceCode(code);permissionRequest.setAction(action);permissionRequest.setName("Project " + action);
            var permission = permissions.create(permissionRequest);
            var binding = new com.auraboot.framework.rbac.entity.RolePermission();
            binding.setPid(UniqueIdGenerator.generate());binding.setRoleId(role.getId());binding.setPermissionId(permission.getId());
            binding.setTenantId(tenant);binding.setDeletedFlag(false);rolePermissions.insert(binding);
        }
        scopes.setScope(tenant, role.getId(), code, "read", "all", "MAX");
        scopes.setScope(tenant, role.getId(), code, "update", "all", "MAX");
        MetaContext.setMemberId(member.getId());
    }

    private ImportExecuteResult install(String code, String version, int threshold) throws Exception {
        var result = imports.executeFromManifest(manifest(code, version, threshold), safe());
        assertThat(result.isSuccess()).as("actual public import result").isTrue();
        return result;
    }

    private ImportRequest safe() {
        var r = new ImportRequest();
        r.setConflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        return r;
    }

    private PluginManifestExtended manifest(String code, String version, int threshold) throws Exception {
        var page = new PageSchemaDTO();
        page.setPageKey(code);
        page.setName("Delivery workspace " + version);
        page.setKind("form");
        page.setSchemaVersion(4);
        page.setTitle(Map.of("zh-CN", "客户项目交付", "en", "Customer delivery"));
        page.setLayout(Map.of("type", "stack"));
        page.setBlocks(List.of(Map.of("id", "summary", "blockType", "rich-text", "content", "Customer delivery workspace")));
        var seed = DecisionDefinitionSeedDTO.builder().decisionCode(code).decisionName("Acceptance approval routing")
                .enabled(true).scopeType("GLOBAL").kind("SIMPLE_CONDITION").runtimeAdapter("AST_EVALUATOR")
                .contentJson(ast(threshold)).publish(true).build();
        return PluginManifestExtended.builder().pluginId(code).namespace(code).version(version)
                .models(List.of(ModelDefinitionDTO.builder().code(code).displayName("Customer projects").modelType("entity").build()))
                .fields(List.of(FieldDefinitionDTO.builder().code(code + "_name").displayName("Project").dataType("string").build(),
                        FieldDefinitionDTO.builder().code(code + "_amount").displayName("Amount").dataType("decimal").build()))
                .modelFieldBindings(List.of(ModelFieldBindingDTO.builder().modelCode(code).fieldCode(code + "_name").aliasCode("name").build(),
                        ModelFieldBindingDTO.builder().modelCode(code).fieldCode(code + "_amount").aliasCode("amount").build()))
                .pages(List.of(page)).decisionDefinitions(List.of(seed)).build();
    }

    private com.fasterxml.jackson.databind.JsonNode ast(int threshold) throws Exception {
        return json.readTree("{\"type\":\"compare\",\"operator\":\"GT\",\"left\":{\"type\":\"path\",\"scope\":\"record\",\"path\":\"data.amount\",\"dataType\":\"integer\"},\"right\":{\"type\":\"literal\",\"value\":" + threshold + ",\"dataType\":\"integer\"}}");
    }

    private void publishCustomerRule(String code, int threshold) throws Exception {
        var request = new DrtVersionCreateRequest();
        request.setKind("SIMPLE_CONDITION");request.setRuntimeAdapter("AST_EVALUATOR");request.setContentJson(ast(threshold));
        var draft = versions.createDraft(code, request);
        assertThat(versions.validate(draft.getPid()).valid()).isTrue();
        versions.publish(draft.getPid(), true);
    }

    private int latestThreshold(String code) {
        return versions.listByCode(code).stream().filter(v -> "PUBLISHED".equals(v.getStatus()))
                .max(Comparator.comparing(DrtVersionDTO::getVersion)).orElseThrow().getContentJson().path("right").path("value").asInt();
    }

    private boolean matches(String code, int amount) {
        var request = new DrtEvaluateRequest();request.setDecisionCode(code);request.setBinding(VersionBinding.LATEST);
        request.setCallerType("API");request.setCallerRef("two-customer-upgrade");
        request.setContext(Map.of("record", Map.of("data", Map.of("amount", amount))));
        var result = evaluation.evaluate(request);
        assertThat(result.errors()).isEmpty();
        return result.matched();
    }
}
