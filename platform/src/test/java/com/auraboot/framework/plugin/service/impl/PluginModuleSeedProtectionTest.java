package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.automation.dto.AutomationDTO;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.decision.dto.*;
import com.auraboot.framework.decision.service.*;
import com.auraboot.framework.eventpolicy.entity.*;
import com.auraboot.framework.eventpolicy.service.*;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.common.constant.ResponseCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginModuleSeedProtectionTest {
    final DrtDefinitionService definitions=mock(DrtDefinitionService.class);
    final DecisionVersionService decisions=mock(DecisionVersionService.class);
    final ConditionFragmentService fragments=mock(ConditionFragmentService.class);
    final EventPolicyDefinitionService policies=mock(EventPolicyDefinitionService.class);
    final EventPolicyVersionService versions=mock(EventPolicyVersionService.class);
    final AutomationService automations=mock(AutomationService.class);
    final PluginRuleSeedImporter rules=new PluginRuleSeedImporter(definitions,decisions,fragments,policies,versions);
    final PluginDefinitionResourceImporter importer=new PluginDefinitionResourceImporter(null,null,automations,null,null);
    final ObjectMapper json=new ObjectMapper();

    @ParameterizedTest @EnumSource(ImportRequest.ConflictStrategy.class)
    void existingModuleDefinitionsHonorImportStrategy(ImportRequest.ConflictStrategy strategy) {
        var request=new ImportRequest();request.setConflictStrategy(strategy);
        var manifest=new PluginManifestExtended();
        manifest.setDecisionDefinitions(List.of(DecisionDefinitionSeedDTO.builder().decisionCode("check").decisionName("Check").kind("VALIDATION").runtimeAdapter("CONDITION").contentJson(json.createObjectNode()).build()));
        manifest.setConditionFragments(List.of(ConditionFragmentSeedDTO.builder().fragmentCode("limit").fragmentName("Limit").conditionSpec(json.createObjectNode()).build()));
        manifest.setEventPolicies(List.of(EventPolicySeedDTO.builder().policyCode("notify").policyName("Notify").eventType("updated").targetType("MODEL").targetKey("invoice").rulesJson(json.createArrayNode().add(json.createObjectNode().put("code","r"))).build()));
        manifest.setPluginId("seed-test");manifest.setAutomations(List.of(AutomationDefinitionDTO.builder().automationKey("remind").name("Remind").modelCode("invoice").triggerType("on_record_update").build()));
        var definition=new DrtDefinitionDTO();definition.setPid("decision");when(definitions.findByCode("check")).thenReturn(definition);
        var fragment=new ConditionFragmentDTO();fragment.setPid("fragment");fragment.setStatus("PUBLISHED");when(fragments.findByCode("limit")).thenReturn(fragment);
        when(policies.findByCode("notify")).thenReturn(new DrtPolicyDefinitionEntity());
        var automation=new AutomationDTO();automation.setPid("automation");automation.setName("Remind");when(automations.getByModelCode("invoice")).thenReturn(List.of(automation));
        // Stop explicit overwrite at the first write; safe/skip/error must never reach this sentinel.
        doThrow(new WriteReached()).when(definitions).update(anyString(),any());
        doThrow(new WriteReached()).when(fragments).createVersion(anyString(),any());
        doThrow(new WriteReached()).when(policies).setEnabled(anyString(),anyBoolean());
        doThrow(new WriteReached()).when(automations).updatePluginSeed(anyString(),any(),anyString(),anyString());
        for(int kind=0;kind<4;kind++) {
            var result=new ImportExecuteResult();final int selected=kind;
            Runnable action=()->{switch(selected){
                case 0 -> rules.importDecisionDefinitions(manifest,request,result);
                case 1 -> rules.importConditionFragments(manifest,request,result);
                case 2 -> rules.importEventPolicies(manifest,request,result);
                case 3 -> importer.importAutomations(manifest,request,result);
                default -> throw new AssertionError();
            }};
            if(strategy==ImportRequest.ConflictStrategy.ERROR) assertThatThrownBy(action::run).isInstanceOf(PluginException.class).hasMessageContaining("already exists");
            else if(strategy==ImportRequest.ConflictStrategy.OVERWRITE) assertThatThrownBy(action::run).isInstanceOf(WriteReached.class);
            else {action.run();assertThat(result.getWarnings()).singleElement().asString().contains(strategy.name());}
        }
        if(strategy!=ImportRequest.ConflictStrategy.OVERWRITE) {
            verify(definitions,never()).update(anyString(),any());verify(fragments,never()).createVersion(anyString(),any());
            verify(policies,never()).setEnabled(anyString(),anyBoolean());verify(versions,never()).createDraft(anyString(),any(),any(),any(),any(),any(),any(),any());verify(automations,never()).updatePluginSeed(anyString(),any(),anyString(),anyString());
        }
    }

    @Test void aFragmentLookupFailureCannotBecomeNotFound() {
        var manifest=new PluginManifestExtended();manifest.setConditionFragments(List.of(ConditionFragmentSeedDTO.builder().fragmentCode("limit").fragmentName("Limit").conditionSpec(json.createObjectNode()).build()));
        var failure=new ValidationException(ResponseCode.CommonValidationFailed,"lookup denied");when(fragments.findByCode("limit")).thenThrow(failure);
        assertThatThrownBy(()->rules.importConditionFragments(manifest,new ImportRequest(),new ImportExecuteResult())).isSameAs(failure);
        verify(fragments,never()).create(any());
    }
    static class WriteReached extends RuntimeException { }

    @ParameterizedTest @EnumSource(ImportRequest.ConflictStrategy.class)
    void everyStrategyAllowsNewModuleDefinitions(ImportRequest.ConflictStrategy strategy) {
        var request=new ImportRequest();request.setConflictStrategy(strategy);var result=new ImportExecuteResult();
        var manifest=new PluginManifestExtended();manifest.setPluginId("seed-test");
        manifest.setAutomations(List.of(AutomationDefinitionDTO.builder().automationKey("new").name("New").modelCode("invoice").triggerType("on_record_update").build()));
        manifest.setDecisionDefinitions(List.of(DecisionDefinitionSeedDTO.builder().decisionCode("new").decisionName("New").kind("SIMPLE_CONDITION").runtimeAdapter("AST_EVALUATOR").contentJson(json.createObjectNode()).publish(false).build()));
        manifest.setConditionFragments(List.of(ConditionFragmentSeedDTO.builder().fragmentCode("new").fragmentName("New").conditionSpec(json.createObjectNode()).publish(false).build()));
        manifest.setEventPolicies(List.of(EventPolicySeedDTO.builder().policyCode("new").policyName("New").eventType("updated").targetType("MODEL").targetKey("invoice").rulesJson(json.createArrayNode().add(json.createObjectNode().put("ruleCode","r"))).publish(false).build()));
        var draft=new DrtVersionDTO();draft.setPid("draft");when(decisions.createDraft(anyString(),any())).thenReturn(draft);
        when(decisions.validate("draft")).thenReturn(com.auraboot.framework.decision.model.DecisionValidateResult.ok(List.of(),List.of()));
        var fragment=new ConditionFragmentDTO();fragment.setPid("fragment");when(fragments.create(any())).thenReturn(fragment);
        importer.importAutomations(manifest,request,result);rules.importDecisionDefinitions(manifest,request,result);rules.importConditionFragments(manifest,request,result);rules.importEventPolicies(manifest,request,result);
        verify(automations).createPluginSeed(any(),eq("seed-test"),eq("new"));verify(definitions).create(any());verify(decisions).createDraft(eq("new"),any());verify(fragments).create(any());verify(policies).create("new","New","updated","MODEL","invoice");
        assertThat(result.getWarnings()).isEmpty();
    }
}
