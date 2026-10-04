package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtDefinitionDTO;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentCreateRequest;
import com.auraboot.framework.decision.dto.ConditionFragmentDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentVersionCreateRequest;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyDefinitionEntity;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.exception.PluginException;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.decision.service.ConditionFragmentService;
import com.auraboot.framework.eventpolicy.service.EventPolicyDefinitionService;
import com.auraboot.framework.eventpolicy.service.EventPolicyVersionService;

/** Imports versioned decision, condition-fragment and event-policy seeds. */
@Slf4j
@RequiredArgsConstructor
final class PluginRuleSeedImporter {
    private final DrtDefinitionService drtDefinitionService;
    private final DecisionVersionService decisionVersionService;
    private final ConditionFragmentService conditionFragmentService;
    private final EventPolicyDefinitionService eventPolicyDefinitionService;
    private final EventPolicyVersionService eventPolicyVersionService;

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }

    void importDecisionDefinitions(PluginManifestExtended manifest) {
        if (manifest.getDecisionDefinitions() == null || manifest.getDecisionDefinitions().isEmpty()) return;
        int imported = 0;
        for (DecisionDefinitionSeedDTO dto : manifest.getDecisionDefinitions()) {
            if (!dto.isValid()) {
                log.warn("Skipping invalid decision definition seed (missing code/name/kind/runtime/content): index={}",
                        manifest.getDecisionDefinitions().indexOf(dto));
                continue;
            }
            importDecisionDefinition(dto);
            imported++;
        }
        if (imported > 0) {
            log.info("Imported {} Decision Runtime definition seed(s) for plugin {}",
                    imported, logSafe(manifest.getPluginId()));
        }
    }

    private void importDecisionDefinition(DecisionDefinitionSeedDTO dto) {
        DrtDefinitionCreateRequest definitionRequest = new DrtDefinitionCreateRequest();
        definitionRequest.setDecisionCode(dto.getDecisionCode());
        definitionRequest.setDecisionName(dto.getDecisionName());
        definitionRequest.setDescription(dto.getDescription());
        definitionRequest.setScopeType(dto.getScopeType());
        definitionRequest.setScopeRef(dto.getScopeRef());
        definitionRequest.setOwnerModule(dto.getOwnerModule());
        definitionRequest.setEnabled(dto.getEnabled());

        DrtDefinitionDTO existing = drtDefinitionService.findByCode(dto.getDecisionCode());
        if (existing == null) {
            drtDefinitionService.create(definitionRequest);
        } else {
            drtDefinitionService.update(existing.getPid(), definitionRequest);
        }

        if (hasMatchingPublishedDecisionVersion(dto)) {
            log.info("Decision seed unchanged; keeping existing published version: code={}",
                    logSafe(dto.getDecisionCode()));
            return;
        }

        DrtVersionCreateRequest versionRequest = new DrtVersionCreateRequest();
        versionRequest.setKind(dto.getKind());
        versionRequest.setRuntimeAdapter(dto.getRuntimeAdapter());
        versionRequest.setVersionTag(dto.getVersionTag());
        versionRequest.setContentJson(dto.getContentJson());
        versionRequest.setInputSchemaJson(dto.getInputSchemaJson());
        versionRequest.setOutputSchemaJson(dto.getOutputSchemaJson());
        versionRequest.setContextSchemaJson(dto.getContextSchemaJson());

        DrtVersionDTO draft = decisionVersionService.createDraft(dto.getDecisionCode(), versionRequest);
        DecisionValidateResult validation = decisionVersionService.validate(draft.getPid());
        if (!validation.valid()) {
            String messages = validation.errors().stream()
                    .map(DecisionValidateResult.Issue::message)
                    .collect(Collectors.joining("; "));
            throw new PluginException("Decision seed validation failed for "
                    + dto.getDecisionCode() + ": " + messages);
        }
        if (dto.isPublish()) {
            decisionVersionService.publish(draft.getPid(), true);
        }
    }

    private boolean hasMatchingPublishedDecisionVersion(DecisionDefinitionSeedDTO dto) {
        List<DrtVersionDTO> versions = decisionVersionService.listByCode(dto.getDecisionCode());
        if (versions == null || versions.isEmpty()) {
            return false;
        }
        return versions.stream().anyMatch(version ->
                "PUBLISHED".equals(version.getStatus())
                        && Objects.equals(version.getKind(), dto.getKind())
                        && Objects.equals(version.getRuntimeAdapter(), dto.getRuntimeAdapter())
                        && Objects.equals(version.getContentJson(), dto.getContentJson())
                        && Objects.equals(version.getInputSchemaJson(), dto.getInputSchemaJson())
                        && Objects.equals(version.getOutputSchemaJson(), dto.getOutputSchemaJson())
                        && Objects.equals(version.getContextSchemaJson(), dto.getContextSchemaJson()));
    }

    void importConditionFragments(PluginManifestExtended manifest) {
        if (manifest.getConditionFragments() == null || manifest.getConditionFragments().isEmpty()) return;
        int imported = 0;
        for (ConditionFragmentSeedDTO dto : manifest.getConditionFragments()) {
            if (!dto.isValid()) {
                log.warn("Skipping invalid condition fragment seed (missing code/name/spec): index={}",
                        manifest.getConditionFragments().indexOf(dto));
                continue;
            }
            importConditionFragment(dto);
            imported++;
        }
        if (imported > 0) {
            log.info("Imported {} condition fragment seed(s) for plugin {}",
                    imported, logSafe(manifest.getPluginId()));
        }
    }

    private void importConditionFragment(ConditionFragmentSeedDTO dto) {
        if (hasMatchingPublishedConditionFragment(dto)) {
            log.info("Condition fragment seed unchanged; keeping existing published version: code={}",
                    logSafe(dto.getFragmentCode()));
            return;
        }

        ConditionFragmentDTO target = findConditionFragmentOrNull(dto.getFragmentCode());
        if (target == null) {
            target = conditionFragmentService.create(toConditionFragmentCreateRequest(dto));
        } else if (isImmutableConditionFragmentStatus(target.getStatus())) {
            target = conditionFragmentService.createVersion(
                    dto.getFragmentCode(), toConditionFragmentVersionCreateRequest(dto));
        } else if (!Objects.equals(target.getConditionSpec(), dto.getConditionSpec())) {
            throw new PluginException("Condition fragment seed conflicts with editable version: "
                    + dto.getFragmentCode()
                    + ". Publish or remove the draft before importing a different seed version.");
        }

        publishConditionFragmentSeedIfRequested(dto, target);
    }

    private ConditionFragmentDTO findConditionFragmentOrNull(String fragmentCode) {
        try {
            return conditionFragmentService.findByCode(fragmentCode);
        } catch (ValidationException e) {
            return null;
        }
    }

    private boolean hasMatchingPublishedConditionFragment(ConditionFragmentSeedDTO dto) {
        List<ConditionFragmentDTO> versions = conditionFragmentService.listVersions(dto.getFragmentCode());
        if (versions == null || versions.isEmpty()) {
            return false;
        }
        return versions.stream().anyMatch(version ->
                "PUBLISHED".equals(version.getStatus())
                        && Objects.equals(version.getFragmentName(), dto.getFragmentName())
                        && Objects.equals(version.getDescription(), dto.getDescription())
                        && Objects.equals(version.getScopeType(), dto.getScopeType())
                        && Objects.equals(version.getScopeRef(), dto.getScopeRef())
                        && Objects.equals(version.getOwnerModule(), dto.getOwnerModule())
                        && Objects.equals(version.getEnabled(), dto.getEnabled())
                        && Objects.equals(version.getConditionSpec(), dto.getConditionSpec()));
    }

    private boolean isImmutableConditionFragmentStatus(String status) {
        return "PUBLISHED".equals(status) || "DEPRECATED".equals(status) || "RETIRED".equals(status);
    }

    private ConditionFragmentCreateRequest toConditionFragmentCreateRequest(ConditionFragmentSeedDTO dto) {
        ConditionFragmentCreateRequest request = new ConditionFragmentCreateRequest();
        request.setFragmentCode(dto.getFragmentCode());
        request.setFragmentName(dto.getFragmentName());
        request.setDescription(dto.getDescription());
        request.setScopeType(dto.getScopeType());
        request.setScopeRef(dto.getScopeRef());
        request.setOwnerModule(dto.getOwnerModule());
        request.setEnabled(dto.getEnabled());
        request.setConditionSpec(dto.getConditionSpec());
        return request;
    }

    private ConditionFragmentVersionCreateRequest toConditionFragmentVersionCreateRequest(ConditionFragmentSeedDTO dto) {
        ConditionFragmentVersionCreateRequest request = new ConditionFragmentVersionCreateRequest();
        request.setFragmentName(dto.getFragmentName());
        request.setDescription(dto.getDescription());
        request.setScopeType(dto.getScopeType());
        request.setScopeRef(dto.getScopeRef());
        request.setOwnerModule(dto.getOwnerModule());
        request.setEnabled(dto.getEnabled());
        request.setConditionSpec(dto.getConditionSpec());
        return request;
    }

    private void publishConditionFragmentSeedIfRequested(ConditionFragmentSeedDTO dto, ConditionFragmentDTO fragment) {
        if (!dto.isPublish() || fragment == null || isImmutableConditionFragmentStatus(fragment.getStatus())) {
            return;
        }
        ConditionFragmentDTO validated = fragment;
        if ("DRAFT".equals(fragment.getStatus())) {
            validated = conditionFragmentService.validate(fragment.getPid());
        }
        if ("VALIDATED".equals(validated.getStatus())) {
            conditionFragmentService.publish(validated.getPid(), true);
        }
    }

    void importEventPolicies(PluginManifestExtended manifest) {
        if (manifest.getEventPolicies() == null || manifest.getEventPolicies().isEmpty()) return;
        int imported = 0;
        for (EventPolicySeedDTO dto : manifest.getEventPolicies()) {
            if (!dto.isValid()) {
                log.warn("Skipping invalid EventPolicy seed (missing definition fields or rulesJson): index={}",
                        manifest.getEventPolicies().indexOf(dto));
                continue;
            }
            importEventPolicy(dto);
            imported++;
        }
        if (imported > 0) {
            log.info("Imported {} EventPolicy seed(s) for plugin {}", imported, logSafe(manifest.getPluginId()));
        }
    }

    private void importEventPolicy(EventPolicySeedDTO dto) {
        DrtPolicyDefinitionEntity definition = eventPolicyDefinitionService.findByCode(dto.getPolicyCode());
        if (definition == null) {
            definition = eventPolicyDefinitionService.create(
                    dto.getPolicyCode(),
                    dto.getPolicyName(),
                    dto.getEventType(),
                    dto.getTargetType(),
                    dto.getTargetKey());
        } else if (dto.getEnabled() != null && !Objects.equals(definition.getEnabled(), dto.getEnabled())) {
            eventPolicyDefinitionService.setEnabled(dto.getPolicyCode(), dto.getEnabled());
        }

        if (hasMatchingPublishedEventPolicyVersion(dto)) {
            log.info("EventPolicy seed unchanged; keeping existing published version: code={}",
                    logSafe(dto.getPolicyCode()));
            return;
        }

        DrtPolicyVersionEntity latest = latestEventPolicyVersion(dto.getPolicyCode());
        DrtPolicyVersionEntity target;
        if (latest == null || isImmutableEventPolicyStatus(latest.getStatus())) {
            target = createEventPolicyDraft(dto);
        } else if (!isSameEventPolicyVersion(latest, dto)) {
            throw new PluginException("EventPolicy seed conflicts with editable version: "
                    + dto.getPolicyCode()
                    + ". Publish or remove the draft before importing a different seed version.");
        } else {
            target = latest;
        }

        publishEventPolicySeedIfRequested(dto, target);
    }

    private DrtPolicyVersionEntity createEventPolicyDraft(EventPolicySeedDTO dto) {
        return eventPolicyVersionService.createDraft(
                dto.getPolicyCode(),
                enumValueOrDefault(dto.getPhase(), PolicyPhase.class, PolicyPhase.AFTER_COMMIT),
                enumValueOrDefault(dto.getMatchMode(), MatchMode.class, MatchMode.COLLECT_ALL),
                enumValueOrDefault(dto.getExecutionMode(), ExecutionMode.class, ExecutionMode.ORDERED),
                enumValueOrDefault(dto.getFailureStrategy(), FailureStrategy.class, FailureStrategy.FAIL_FAST),
                enumValueOrDefault(dto.getConflictStrategy(), ConflictStrategy.class, ConflictStrategy.REJECT_ON_CONFLICT),
                enumValueOrDefault(dto.getDedupStrategy(), DedupStrategy.class, DedupStrategy.BY_IDEMPOTENCY_KEY),
                dto.getRulesJson());
    }

    private boolean hasMatchingPublishedEventPolicyVersion(EventPolicySeedDTO dto) {
        List<DrtPolicyVersionEntity> versions = eventPolicyVersionService.listByCode(dto.getPolicyCode());
        if (versions == null || versions.isEmpty()) {
            return false;
        }
        return versions.stream().anyMatch(version ->
                "PUBLISHED".equals(version.getStatus()) && isSameEventPolicyVersion(version, dto));
    }

    private DrtPolicyVersionEntity latestEventPolicyVersion(String policyCode) {
        List<DrtPolicyVersionEntity> versions = eventPolicyVersionService.listByCode(policyCode);
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        return versions.get(versions.size() - 1);
    }

    private boolean isSameEventPolicyVersion(DrtPolicyVersionEntity version, EventPolicySeedDTO dto) {
        return Objects.equals(version.getPhase(), enumValueOrDefault(dto.getPhase(), PolicyPhase.class, PolicyPhase.AFTER_COMMIT).name())
                && Objects.equals(version.getMatchMode(), enumValueOrDefault(dto.getMatchMode(), MatchMode.class, MatchMode.COLLECT_ALL).name())
                && Objects.equals(version.getExecutionMode(), enumValueOrDefault(dto.getExecutionMode(), ExecutionMode.class, ExecutionMode.ORDERED).name())
                && Objects.equals(version.getFailureStrategy(), enumValueOrDefault(dto.getFailureStrategy(), FailureStrategy.class, FailureStrategy.FAIL_FAST).name())
                && Objects.equals(version.getConflictStrategy(), enumValueOrDefault(dto.getConflictStrategy(), ConflictStrategy.class, ConflictStrategy.REJECT_ON_CONFLICT).name())
                && Objects.equals(version.getDedupStrategy(), enumValueOrDefault(dto.getDedupStrategy(), DedupStrategy.class, DedupStrategy.BY_IDEMPOTENCY_KEY).name())
                && Objects.equals(version.getRulesJson(), dto.getRulesJson());
    }

    private boolean isImmutableEventPolicyStatus(String status) {
        return "PUBLISHED".equals(status) || "DEPRECATED".equals(status) || "RETIRED".equals(status);
    }

    private void publishEventPolicySeedIfRequested(EventPolicySeedDTO dto, DrtPolicyVersionEntity version) {
        if (!dto.isPublish() || version == null || isImmutableEventPolicyStatus(version.getStatus())) {
            return;
        }
        DrtPolicyVersionEntity validated = version;
        if ("DRAFT".equals(version.getStatus())) {
            validated = eventPolicyVersionService.validate(version.getPid());
        }
        if ("VALIDATED".equals(validated.getStatus())) {
            eventPolicyVersionService.publish(validated.getPid());
        }
    }

    private <T extends Enum<T>> T enumValueOrDefault(String value, Class<T> enumType, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Enum.valueOf(enumType, value);
    }

}
