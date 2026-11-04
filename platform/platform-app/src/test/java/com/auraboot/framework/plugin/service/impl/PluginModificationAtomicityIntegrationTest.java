package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.controller.config.CommandController;
import com.auraboot.framework.meta.controller.config.FieldController;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.automation.dto.*;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.decision.service.*;
import com.auraboot.framework.decision.dto.*;
import com.auraboot.framework.eventpolicy.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual Spring controller/service transactions; only the marker failure is injected. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PluginModificationAtomicityIntegrationTest extends BaseIntegrationTest {
    @Autowired CommandController commandController;
    @Autowired FieldController fieldController;
    @Autowired CommandService commands;
    @Autowired MetaFieldMapper fields;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean PluginResourceTracker tracker;
    @Autowired AutomationService automations;
    @Autowired DrtDefinitionService definitions;
    @Autowired DecisionVersionService decisions;
    @Autowired ConditionFragmentService fragments;
    @Autowired EventPolicyDefinitionService policies;
    @Autowired EventPolicyVersionService versions;
    @Autowired ObjectMapper json;

    private ImportRequest request(ImportRequest.ConflictStrategy strategy) {
        var request=new ImportRequest();request.setConflictStrategy(strategy);return request;
    }

    @ParameterizedTest @EnumSource(ImportRequest.ConflictStrategy.class)
    void automationCustomerEditSurvivesProtectedReimport(ImportRequest.ConflictStrategy strategy) {
        String key="seed_"+UniqueIdGenerator.generate().toLowerCase();
        var seed=AutomationDefinitionDTO.builder().automationKey(key).name(key).modelCode(key)
                .triggerType("on_record_update").description("vendor original").enabled(false)
                .actions(List.of(AutomationAction.builder().type("delay").config(Map.of("seconds",1)).sequence(0).build())).build();
        var manifest=new PluginManifestExtended();manifest.setPluginId("atomic-seed-test");manifest.setAutomations(List.of(seed));
        var importer=new PluginDefinitionResourceImporter(null,null,automations,null,null);
        importer.importAutomations(manifest,request(ImportRequest.ConflictStrategy.ERROR),new ImportExecuteResult());
        var existing=automations.getByModelCode(key).getFirst();
        var edit=new AutomationUpdateRequest();edit.setName("Customer renamed " + key);edit.setDescription("customer customization");automations.update(existing.getPid(),edit);
        seed.setDescription("vendor upgrade");var result=new ImportExecuteResult();
        if(strategy==ImportRequest.ConflictStrategy.ERROR) assertThatThrownBy(()->importer.importAutomations(manifest,request(strategy),result)).hasMessageContaining("already exists");
        else importer.importAutomations(manifest,request(strategy),result);
        assertThat(automations.findByPid(existing.getPid()).getDescription()).isEqualTo(strategy==ImportRequest.ConflictStrategy.OVERWRITE?"vendor upgrade":"customer customization");
        assertThat(automations.findByPid(existing.getPid()).getEnabled()).isFalse();
        assertThat(automations.getByModelCode(key)).hasSize(1);
        if(strategy==ImportRequest.ConflictStrategy.SKIP || strategy==ImportRequest.ConflictStrategy.OVERWRITE_SAFE) {
            importer.importAutomations(manifest,request(strategy),new ImportExecuteResult());
            assertThat(automations.findByPid(existing.getPid()).getDescription()).isEqualTo("customer customization");
            assertThat(result.getWarnings()).hasSize(1);
        }
    }

    @ParameterizedTest @EnumSource(ImportRequest.ConflictStrategy.class)
    void decisionCustomerMetadataAndVersionsSurviveProtectedReimport(ImportRequest.ConflictStrategy strategy) throws Exception {
        String code="seed_"+UniqueIdGenerator.generate().toLowerCase();
        var seed=DecisionDefinitionSeedDTO.builder().decisionCode(code).decisionName("Vendor name").enabled(false)
                .scopeType("GLOBAL").kind("SIMPLE_CONDITION").runtimeAdapter("AST_EVALUATOR")
                .contentJson(json.readTree("{\"type\":\"compare\",\"operator\":\"EQ\",\"left\":{\"type\":\"literal\",\"value\":1,\"dataType\":\"integer\"},\"right\":{\"type\":\"literal\",\"value\":1,\"dataType\":\"integer\"}}"))
                .publish(false).build();
        var manifest=new PluginManifestExtended();manifest.setDecisionDefinitions(List.of(seed));
        var importer=new PluginRuleSeedImporter(definitions,decisions,fragments,policies,versions);
        importer.importDecisionDefinitions(manifest,request(ImportRequest.ConflictStrategy.ERROR),new ImportExecuteResult());
        var existing=definitions.findByCode(code);var edit=new DrtDefinitionCreateRequest();edit.setDecisionCode(code);edit.setDecisionName("Customer name");edit.setEnabled(false);edit.setScopeType("GLOBAL");definitions.update(existing.getPid(),edit);
        int count=decisions.listByCode(code).size();seed.setDecisionName("Vendor upgrade");
        var result=new ImportExecuteResult();
        if(strategy==ImportRequest.ConflictStrategy.ERROR) assertThatThrownBy(()->importer.importDecisionDefinitions(manifest,request(strategy),result)).hasMessageContaining("already exists");
        else importer.importDecisionDefinitions(manifest,request(strategy),result);
        assertThat(definitions.findByCode(code).getDecisionName()).isEqualTo(strategy==ImportRequest.ConflictStrategy.OVERWRITE?"Vendor upgrade":"Customer name");
        if(strategy!=ImportRequest.ConflictStrategy.OVERWRITE) assertThat(decisions.listByCode(code)).hasSize(count);
    }


    private void track(ResourceType type,String code,String pid) {
        long tenant=MetaContext.getCurrentTenantId();String plugin=UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_plugin(id,pid,tenant_id,plugin_id,namespace,version,manifest) VALUES (?,?,?, ?,?,'1.0.0','{}')",System.nanoTime(),plugin,tenant,code,code);
        jdbc.update("INSERT INTO ab_plugin_resource(id,pid,tenant_id,plugin_pid,resource_type,resource_code,resource_pid,user_modified,import_snapshot) VALUES (?,?,?,?,?,?,?,false,'{}')",System.nanoTime(),UniqueIdGenerator.generate(),tenant,plugin,type.code(),code,pid);
    }
    private boolean modified(String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT user_modified FROM ab_plugin_resource WHERE tenant_id=? AND resource_code=?",Boolean.class,MetaContext.getCurrentTenantId(),code));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void commandSaveAndProtectionCommitTogether(boolean failMarker) {
        String code="atomic_"+UniqueIdGenerator.generate().toLowerCase();
        var request=new CommandDefinitionCreateRequest();request.setCode(code);request.setModelCode("test_model");request.setDescription("original");
        var command=commands.create(request);track(ResourceType.COMMAND,code,command.getPid());
        request.setDescription("customer edit");
        if(failMarker) {
            doThrow(new IllegalStateException("marker unavailable")).when(tracker).markAsUserModified(ResourceType.COMMAND,code);
            assertThatThrownBy(()->commandController.update(command.getPid(),request)).isInstanceOf(IllegalStateException.class).hasMessage("marker unavailable");
        } else commandController.update(command.getPid(),request);
        assertThat(jdbc.queryForObject("SELECT description FROM ab_command_definition WHERE pid=?",String.class,command.getPid())).isEqualTo(failMarker?"original":"customer edit");
        assertThat(modified(code)).isEqualTo(!failMarker);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void fieldVersionAndProtectionCommitTogether(boolean failMarker) {
        String code="atomic_"+UniqueIdGenerator.generate().toLowerCase();
        var field=new Field();field.setPid(UniqueIdGenerator.generate());field.setTenantId(MetaContext.getCurrentTenantId());field.setCode(code);field.setDataType("string");field.setVersion(1);field.setStatus("draft");field.setIsCurrent(true);field.setDeletedFlag(false);field.setCreatedAt(Instant.now());field.setUpdatedAt(Instant.now());fields.insert(field);
        track(ResourceType.FIELD,code,field.getPid());
        var request=new MetaFieldUpdateRequest();request.setDataType("string");
        if(failMarker) {
            doThrow(new IllegalStateException("marker unavailable")).when(tracker).markAsUserModified(ResourceType.FIELD,code);
            assertThatThrownBy(()->fieldController.updateField(field.getPid(),request)).isInstanceOf(IllegalStateException.class).hasMessage("marker unavailable");
        } else fieldController.updateField(field.getPid(),request);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_meta_field WHERE tenant_id=? AND code=?",Integer.class,MetaContext.getCurrentTenantId(),code)).isEqualTo(failMarker?1:2);
        assertThat(jdbc.queryForObject("SELECT is_current FROM ab_meta_field WHERE pid=?",Boolean.class,field.getPid())).isEqualTo(failMarker);
        assertThat(modified(code)).isEqualTo(!failMarker);
    }

    private com.fasterxml.jackson.databind.JsonNode constantCondition() throws Exception {
        return json.readTree("{\"type\":\"compare\",\"operator\":\"EQ\",\"left\":{\"type\":\"literal\",\"value\":1,\"dataType\":\"integer\"},\"right\":{\"type\":\"literal\",\"value\":1,\"dataType\":\"integer\"}}");
    }

    @ParameterizedTest @EnumSource(ImportRequest.ConflictStrategy.class)
    void publishedFragmentRemainsUnchangedUnlessExplicitlyOverwritten(ImportRequest.ConflictStrategy strategy) throws Exception {
        String code="fragment_"+UniqueIdGenerator.generate().toLowerCase();
        var spec=json.createObjectNode().set("root",constantCondition());
        var create=new ConditionFragmentCreateRequest();create.setFragmentCode(code);create.setFragmentName("Customer fragment");create.setConditionSpec(spec);create.setScopeType("GLOBAL");
        var original=fragments.create(create);fragments.validate(original.getPid());fragments.publish(original.getPid(),true);
        var seed=ConditionFragmentSeedDTO.builder().fragmentCode(code).fragmentName("Vendor upgrade").conditionSpec(spec).scopeType("GLOBAL").publish(false).build();
        var manifest=new PluginManifestExtended();manifest.setConditionFragments(List.of(seed));
        var importer=new PluginRuleSeedImporter(definitions,decisions,fragments,policies,versions);
        if(strategy==ImportRequest.ConflictStrategy.ERROR) assertThatThrownBy(()->importer.importConditionFragments(manifest,request(strategy),new ImportExecuteResult())).hasMessageContaining("already exists");
        else importer.importConditionFragments(manifest,request(strategy),new ImportExecuteResult());
        assertThat(fragments.findByCode(code).getFragmentName()).isEqualTo(strategy==ImportRequest.ConflictStrategy.OVERWRITE?"Vendor upgrade":"Customer fragment");
        assertThat(fragments.listVersions(code)).hasSize(strategy==ImportRequest.ConflictStrategy.OVERWRITE?2:1);
    }

    @ParameterizedTest @EnumSource(ImportRequest.ConflictStrategy.class)
    void eventPolicyEnabledStateAndVersionsHonorImportStrategy(ImportRequest.ConflictStrategy strategy) {
        String code="policy_"+UniqueIdGenerator.generate().toLowerCase();
        policies.create(code,"Customer policy","updated","MODEL","test_model");policies.setEnabled(code,false);
        var seed=EventPolicySeedDTO.builder().policyCode(code).policyName("Vendor policy").eventType("updated").targetType("MODEL").targetKey("test_model").enabled(true).publish(false)
                .rulesJson(json.createArrayNode().add(json.createObjectNode().put("ruleCode","r").put("enabled",false).set("actions",json.createArrayNode()))).build();
        var manifest=new PluginManifestExtended();manifest.setEventPolicies(List.of(seed));
        var importer=new PluginRuleSeedImporter(definitions,decisions,fragments,policies,versions);
        if(strategy==ImportRequest.ConflictStrategy.ERROR) assertThatThrownBy(()->importer.importEventPolicies(manifest,request(strategy),new ImportExecuteResult())).hasMessageContaining("already exists");
        else importer.importEventPolicies(manifest,request(strategy),new ImportExecuteResult());
        assertThat(policies.findByCode(code).getEnabled()).isEqualTo(strategy==ImportRequest.ConflictStrategy.OVERWRITE);
        assertThat(versions.listByCode(code)).hasSize(strategy==ImportRequest.ConflictStrategy.OVERWRITE?1:0);
    }

    @org.junit.jupiter.api.Test
    void stableAutomationIdentityIsTenantScopedImmutableAndDuplicateSafe() {
        String code="identity_"+UniqueIdGenerator.generate().toLowerCase();
        var create=new AutomationCreateRequest();create.setName(code);create.setModelCode(code);create.setTriggerType("on_record_update");create.setEnabled(false);create.setActions(List.of(AutomationAction.builder().type("delay").config(Map.of("seconds",1)).sequence(0).build()));
        var first=automations.createPluginSeed(create,"identity-test",code);
        assertThatThrownBy(()->automations.createPluginSeed(create,"identity-test",code)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(automations.getByModelCode(code)).hasSize(1);
        assertThatThrownBy(()->jdbc.update("UPDATE ab_automation SET seed_key=? WHERE pid=?",code+"_forged",first.getPid())).hasMessageContaining("immutable");
        long tenant=MetaContext.getCurrentTenantId();long second=System.nanoTime();String user=MetaContext.getCurrentUserPid();Long userId=MetaContext.getCurrentUserId();
        jdbc.update("INSERT INTO ab_tenant(id,pid,name) VALUES (?,?,?)",second,UniqueIdGenerator.generate(),code);
        try {
            MetaContext.setContext(second,userId,user,"Identity reviewer");
            assertThat(automations.findPluginSeed("identity-test",code)).isNull();
            var other=automations.createPluginSeed(create,"identity-test",code);
            assertThat(other.getPid()).isNotEqualTo(first.getPid());
            assertThat(automations.findPluginSeed("identity-test",code).getPid()).isEqualTo(other.getPid());
        } finally {MetaContext.setContext(tenant,userId,user,"Identity reviewer");}
        assertThat(automations.findPluginSeed("identity-test",code).getPid()).isEqualTo(first.getPid());
    }

    @org.junit.jupiter.api.Test
    void unknownLegacyAutomationNamesArePreservedUntilExplicitAdoption() {
        String code="legacy_"+UniqueIdGenerator.generate().toLowerCase();
        var create=new AutomationCreateRequest();create.setName("Customer renamed "+code);create.setModelCode(code);create.setDescription("customer version");create.setTriggerType("on_record_update");create.setEnabled(false);create.setActions(List.of(AutomationAction.builder().type("delay").config(Map.of("seconds",1)).sequence(0).build()));
        var legacy=automations.create(create);
        var seed=AutomationDefinitionDTO.builder().automationKey(code).name("Vendor "+code).description("vendor version").modelCode(code).triggerType("on_record_update").enabled(false).actions(create.getActions()).build();
        var manifest=new PluginManifestExtended();manifest.setPluginId("legacy-seed-test");manifest.setAutomations(List.of(seed));
        var importer=new PluginDefinitionResourceImporter(null,null,automations,null,null);var result=new ImportExecuteResult();
        importer.importAutomations(manifest,request(ImportRequest.ConflictStrategy.OVERWRITE_SAFE),result);
        assertThat(result.getWarnings()).singleElement().asString().contains("ambiguous legacy");
        assertThat(automations.getByModelCode(code)).hasSize(1);assertThat(automations.findByPid(legacy.getPid()).getDescription()).isEqualTo("customer version");
        seed.setName(create.getName());importer.importAutomations(manifest,request(ImportRequest.ConflictStrategy.OVERWRITE),new ImportExecuteResult());
        assertThat(automations.findPluginSeed("legacy-seed-test",code).getPid()).isEqualTo(legacy.getPid());
    }
}
