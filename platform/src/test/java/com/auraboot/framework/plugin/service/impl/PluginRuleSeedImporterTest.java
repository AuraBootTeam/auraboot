package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.decision.dto.*;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.service.*;
import com.auraboot.framework.eventpolicy.entity.*;
import com.auraboot.framework.eventpolicy.model.*;
import com.auraboot.framework.eventpolicy.service.*;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.exception.PluginException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Locks versioned seed identity, immutable-version preservation and publication errors. */
class PluginRuleSeedImporterTest {
    final DrtDefinitionService definitions = mock(DrtDefinitionService.class);
    final DecisionVersionService decisions = mock(DecisionVersionService.class);
    final ConditionFragmentService fragments = mock(ConditionFragmentService.class);
    final EventPolicyDefinitionService policies = mock(EventPolicyDefinitionService.class);
    final EventPolicyVersionService versions = mock(EventPolicyVersionService.class);
    final PluginRuleSeedImporter importer = new PluginRuleSeedImporter(definitions, decisions, fragments, policies, versions);
    final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void decisionSeedCreatesValidatedDraftAndOnlyPublishesWhenRequested(boolean publish) {
        var seed = DecisionDefinitionSeedDTO.builder().decisionCode("invoice_rule").decisionName("Invoice rule")
                .kind("VALIDATION").runtimeAdapter("CONDITION").contentJson(json.createObjectNode()).publish(publish).build();
        var draft = new DrtVersionDTO(); draft.setPid("decision-draft");
        when(decisions.createDraft(eq("invoice_rule"), any())).thenReturn(draft);
        when(decisions.validate("decision-draft")).thenReturn(DecisionValidateResult.ok(List.of(), List.of()));
        var manifest = new PluginManifestExtended(); manifest.setDecisionDefinitions(List.of(seed));
        importer.importDecisionDefinitions(manifest);
        verify(definitions).create(argThat(request -> "invoice_rule".equals(request.getDecisionCode())));
        verify(decisions).createDraft(eq("invoice_rule"), argThat(request -> request.getContentJson().equals(seed.getContentJson())));
        verify(decisions, times(publish ? 1 : 0)).publish("decision-draft", true);
        var existing = new DrtDefinitionDTO(); existing.setPid("definition-42");
        when(definitions.findByCode("invoice_rule")).thenReturn(existing);
        draft.setStatus("PUBLISHED"); draft.setKind(seed.getKind()); draft.setRuntimeAdapter(seed.getRuntimeAdapter());
        draft.setContentJson(seed.getContentJson()); when(decisions.listByCode("invoice_rule")).thenReturn(List.of(draft));
        clearInvocations(definitions, decisions);
        importer.importDecisionDefinitions(manifest);
        verify(definitions).update(eq("definition-42"), any());
        verify(decisions, never()).createDraft(anyString(), any());
    }

    @Test
    void invalidDecisionValidationStopsBeforePublication() {
        var seed = DecisionDefinitionSeedDTO.builder().decisionCode("bad_rule").decisionName("Bad rule")
                .kind("VALIDATION").runtimeAdapter("CONDITION").contentJson(json.createObjectNode()).build();
        var draft = new DrtVersionDTO(); draft.setPid("draft-bad");
        when(decisions.createDraft(anyString(), any())).thenReturn(draft);
        when(decisions.validate("draft-bad")).thenReturn(DecisionValidateResult.invalid(List.of(new DecisionValidateResult.Issue("BAD", "broken field"))));
        var manifest = new PluginManifestExtended(); manifest.setDecisionDefinitions(List.of(seed));
        assertThatThrownBy(() -> importer.importDecisionDefinitions(manifest)).isInstanceOf(PluginException.class).hasMessageContaining("broken field");
        verify(decisions, never()).publish(anyString(), anyBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PUBLISHED", "DEPRECATED", "RETIRED"})
    void fragmentImportPreservesImmutableVersionsAndRejectsConflictingDraft(String status) {
        var seed = ConditionFragmentSeedDTO.builder().fragmentCode("amount_positive").fragmentName("Amount positive")
                .description("Invoice condition").scopeType("MODEL").scopeRef("invoice").ownerModule("billing")
                .conditionSpec(json.createObjectNode().put("op", "GT")).build();
        var existing = new ConditionFragmentDTO(); existing.setPid("fragment-old"); existing.setStatus(status);
        existing.setConditionSpec(json.createObjectNode().put("op", "LT"));
        when(fragments.findByCode("amount_positive")).thenReturn(existing);
        var draft = new ConditionFragmentDTO(); draft.setPid("fragment-new"); draft.setStatus("DRAFT");
        var validated = new ConditionFragmentDTO(); validated.setPid("fragment-new"); validated.setStatus("VALIDATED");
        var manifest = new PluginManifestExtended(); manifest.setConditionFragments(List.of(seed));
        if ("DRAFT".equals(status)) {
            assertThatThrownBy(() -> importer.importConditionFragments(manifest)).isInstanceOf(PluginException.class).hasMessageContaining("editable version");
            verify(fragments, never()).publish(anyString(), anyBoolean());
        } else {
            when(fragments.createVersion(eq("amount_positive"), any())).thenReturn(draft);
            when(fragments.validate("fragment-new")).thenReturn(validated);
            importer.importConditionFragments(manifest);
            verify(fragments).createVersion(eq("amount_positive"), argThat(request -> seed.getConditionSpec().equals(request.getConditionSpec())));
            verify(fragments).publish("fragment-new", true);
        }
    }

    @Test
    void newFragmentCopiesDefinitionAndPublicationMetadata() {
        var seed = ConditionFragmentSeedDTO.builder().fragmentCode("new_fragment").fragmentName("New fragment")
                .description("Reusable").scopeType("MODEL").scopeRef("invoice").ownerModule("billing")
                .conditionSpec(json.createObjectNode().put("op", "EQ")).build();
        var draft = new ConditionFragmentDTO(); draft.setPid("fragment-new"); draft.setStatus("VALIDATED");
        when(fragments.create(any())).thenReturn(draft);
        var manifest = new PluginManifestExtended(); manifest.setConditionFragments(List.of(seed));
        importer.importConditionFragments(manifest);
        verify(fragments).create(argThat(request -> "billing".equals(request.getOwnerModule()) && "invoice".equals(request.getScopeRef())));
        verify(fragments).publish("fragment-new", true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void eventSeedCreatesDefaultVersionAndOnlyPublishesWhenRequested(boolean publish) {
        var seed = EventPolicySeedDTO.builder().policyCode("invoice_updated").policyName("Invoice updated")
                .eventType("updated").targetType("MODEL").targetKey("invoice")
                .rulesJson(json.createArrayNode().add(json.createObjectNode().put("code", "notify"))).publish(publish).build();
        var draft = new DrtPolicyVersionEntity(); draft.setPid("event-draft"); draft.setStatus("DRAFT");
        var validated = new DrtPolicyVersionEntity(); validated.setPid("event-draft"); validated.setStatus("VALIDATED");
        when(versions.createDraft(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(draft);
        when(versions.validate("event-draft")).thenReturn(validated);
        var manifest = new PluginManifestExtended(); manifest.setEventPolicies(List.of(seed));
        importer.importEventPolicies(manifest);
        verify(policies).create("invoice_updated", "Invoice updated", "updated", "MODEL", "invoice");
        verify(versions).createDraft("invoice_updated", PolicyPhase.AFTER_COMMIT, MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED, FailureStrategy.FAIL_FAST, ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY, seed.getRulesJson());
        verify(versions, times(publish ? 1 : 0)).publish("event-draft");
    }

    @Test
    void unchangedPublishedEventSeedKeepsVersionAndSynchronizesEnabledFlag() {
        var seed = EventPolicySeedDTO.builder().policyCode("event_rule").policyName("Event rule").eventType("updated")
                .targetType("MODEL").targetKey("invoice").enabled(false)
                .rulesJson(json.createArrayNode().add(json.createObjectNode().put("code", "rule"))).build();
        var definition = new DrtPolicyDefinitionEntity(); definition.setEnabled(true);
        when(policies.findByCode("event_rule")).thenReturn(definition);
        var published = new DrtPolicyVersionEntity(); published.setStatus("PUBLISHED");
        published.setPhase("AFTER_COMMIT"); published.setMatchMode("COLLECT_ALL"); published.setExecutionMode("ORDERED");
        published.setFailureStrategy("FAIL_FAST"); published.setConflictStrategy("REJECT_ON_CONFLICT");
        published.setDedupStrategy("BY_IDEMPOTENCY_KEY"); published.setRulesJson(seed.getRulesJson());
        when(versions.listByCode("event_rule")).thenReturn(List.of(published));
        var manifest = new PluginManifestExtended(); manifest.setEventPolicies(List.of(seed));
        importer.importEventPolicies(manifest);
        verify(policies).setEnabled("event_rule", false);
        verify(versions, never()).createDraft(anyString(), any(), any(), any(), any(), any(), any(), any());
    }
}
