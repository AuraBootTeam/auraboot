package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.mapper.SlaConfigMapper;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.dto.DecisionImpactRefDTO;
import com.auraboot.framework.decision.dto.DecisionUsageIndexRebuildDTO;
import com.auraboot.framework.decision.entity.DecisionUsageRefEntity;
import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.mapper.DecisionUsageRefMapper;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyDefinitionEntity;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyDefinitionMapper;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyVersionMapper;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rebuildable usage-index projection for DecisionOps impact analysis.
 */
@Service
@RequiredArgsConstructor
public class DecisionUsageIndexServiceImpl implements DecisionUsageIndexService {

    private static final Set<String> INDEXABLE_VERSION_STATUSES = Set.of(
            "VALIDATED", "PENDING_APPROVAL", "PUBLISHED", "DEPRECATED");
    private static final Set<String> SUPPORTED_SOURCE_TYPES = Set.of(
            "DECISION_VERSION", "AUTOMATION", "SLA_RULE", "EVENT_POLICY", "NAMED_QUERY");

    private final DecisionUsageRefMapper usageRefMapper;
    private final DrtVersionMapper versionMapper;
    private final AutomationMapper automationMapper;
    private final SlaConfigMapper slaConfigMapper;
    private final DrtPolicyVersionMapper policyVersionMapper;
    private final DrtPolicyDefinitionMapper policyDefinitionMapper;
    private final NamedQueryMapper namedQueryMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public DecisionUsageIndexRebuildDTO rebuild() {
        Long tenantId = requireTenant();
        usageRefMapper.deleteByTenant(tenantId);

        List<DecisionUsageRefEntity> refs = new ArrayList<>();
        refs.addAll(scanDecisionVersions(tenantId));
        refs.addAll(scanAutomations(tenantId));
        refs.addAll(scanSlaRules(tenantId));
        refs.addAll(scanEventPolicies(tenantId));
        refs.addAll(scanNamedQueries(tenantId));

        return insertRefs(tenantId, refs);
    }

    @Override
    @Transactional
    public DecisionUsageIndexRebuildDTO refreshDecisionVersion(String versionPid) {
        Long tenantId = requireTenant();
        return refreshDecisionVersion(tenantId, versionPid);
    }

    @Override
    @Transactional
    public DecisionUsageIndexRebuildDTO refreshSource(String sourceType, String sourcePid) {
        Long tenantId = requireTenant();
        String normalized = normalizeSourceType(sourceType);
        return switch (normalized) {
            case "DECISION_VERSION" -> refreshDecisionVersion(tenantId, sourcePid);
            case "AUTOMATION" -> refreshAutomation(tenantId, sourcePid);
            case "SLA_RULE" -> refreshSlaRule(tenantId, sourcePid);
            case "EVENT_POLICY" -> refreshEventPolicyVersion(tenantId, sourcePid);
            case "NAMED_QUERY" -> refreshNamedQuery(tenantId, sourcePid);
            default -> throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Unsupported usage-index source type: " + sourceType);
        };
    }

    @Override
    @Transactional
    public DecisionUsageIndexRebuildDTO deleteSource(String sourceType, String sourcePid) {
        Long tenantId = requireTenant();
        String normalized = normalizeSourceType(sourceType);
        usageRefMapper.deleteBySource(tenantId, normalized, sourcePid);
        return newSummary(tenantId);
    }

    private DecisionUsageIndexRebuildDTO refreshDecisionVersion(Long tenantId, String versionPid) {
        DrtVersionEntity version = versionMapper.selectOne(new LambdaQueryWrapper<DrtVersionEntity>()
                .eq(DrtVersionEntity::getTenantId, tenantId)
                .eq(DrtVersionEntity::getPid, versionPid));
        if (version == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Decision version not found: " + versionPid);
        }

        usageRefMapper.deleteBySource(tenantId, "DECISION_VERSION", versionPid);
        List<DecisionUsageRefEntity> refs = INDEXABLE_VERSION_STATUSES.contains(nullToBlank(version.getStatus()))
                ? refsForVersion(tenantId, version)
                : List.of();
        return insertRefs(tenantId, refs);
    }

    private DecisionUsageIndexRebuildDTO refreshAutomation(Long tenantId, String automationPid) {
        Automation automation = automationMapper.selectOne(new LambdaQueryWrapper<Automation>()
                .eq(Automation::getTenantId, tenantId)
                .eq(Automation::getPid, automationPid)
                .eq(Automation::getDeletedFlag, false));
        if (automation == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Automation source not found: " + automationPid);
        }

        usageRefMapper.deleteBySource(tenantId, "AUTOMATION", automationPid);
        return insertRefs(tenantId, refsForAutomation(tenantId, automation));
    }

    private DecisionUsageIndexRebuildDTO refreshSlaRule(Long tenantId, String slaPid) {
        SlaConfigEntity sla = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfigEntity>()
                .eq(SlaConfigEntity::getTenantId, tenantId)
                .eq(SlaConfigEntity::getPid, slaPid)
                .eq(SlaConfigEntity::getDeletedFlag, false));
        if (sla == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "SLA rule source not found: " + slaPid);
        }

        usageRefMapper.deleteBySource(tenantId, "SLA_RULE", slaPid);
        return insertRefs(tenantId, refsForSlaRule(tenantId, sla));
    }

    private DecisionUsageIndexRebuildDTO refreshEventPolicyVersion(Long tenantId, String versionPid) {
        DrtPolicyVersionEntity version = policyVersionMapper.selectOne(new LambdaQueryWrapper<DrtPolicyVersionEntity>()
                .eq(DrtPolicyVersionEntity::getTenantId, tenantId)
                .eq(DrtPolicyVersionEntity::getPid, versionPid));
        if (version == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Event policy version source not found: " + versionPid);
        }

        usageRefMapper.deleteBySource(tenantId, "EVENT_POLICY", versionPid);
        List<DecisionUsageRefEntity> refs = INDEXABLE_VERSION_STATUSES.contains(nullToBlank(version.getStatus()))
                ? refsForEventPolicyVersion(tenantId, version)
                : List.of();
        return insertRefs(tenantId, refs);
    }

    private DecisionUsageIndexRebuildDTO refreshNamedQuery(Long tenantId, String queryPid) {
        NamedQuery query = namedQueryMapper.selectOne(new LambdaQueryWrapper<NamedQuery>()
                .eq(NamedQuery::getTenantId, tenantId)
                .eq(NamedQuery::getPid, queryPid));
        if (query == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "NamedQuery source not found: " + queryPid);
        }

        usageRefMapper.deleteBySource(tenantId, "NAMED_QUERY", queryPid);
        return insertRefs(tenantId, refsForNamedQuery(tenantId, query));
    }

    @Override
    public List<DecisionImpactRefDTO> findIncomingDecisionRefs(String decisionCode) {
        Long tenantId = requireTenant();
        return usageRefMapper.findIncomingDecisionRefs(tenantId, decisionCode).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public List<DecisionImpactRefDTO> findOutgoingDecisionRefs(String decisionCode) {
        Long tenantId = requireTenant();
        return usageRefMapper.findOutgoingDecisionRefs(tenantId, decisionCode).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public List<DecisionImpactRefDTO> findFieldRefs(String fieldRef) {
        Long tenantId = requireTenant();
        return usageRefMapper.findFieldRefs(tenantId, fieldRef).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public List<DecisionImpactRefDTO> findTargetRefs(String targetType, String targetCode) {
        Long tenantId = requireTenant();
        String normalizedTargetType = normalizeTargetType(targetType);
        if (nullToBlank(targetCode).isBlank()) {
            return List.of();
        }
        return usageRefMapper.findTargetRefs(tenantId, normalizedTargetType, targetCode).stream()
                .map(this::toDto)
                .toList();
    }

    private List<DecisionUsageRefEntity> scanDecisionVersions(Long tenantId) {
        return versionMapper.selectList(new LambdaQueryWrapper<DrtVersionEntity>()
                        .eq(DrtVersionEntity::getTenantId, tenantId)
                        .in(DrtVersionEntity::getStatus, INDEXABLE_VERSION_STATUSES))
                .stream()
                .flatMap(version -> refsForVersion(tenantId, version).stream())
                .toList();
    }

    private List<DecisionUsageRefEntity> refsForVersion(Long tenantId, DrtVersionEntity version) {
        List<DecisionUsageRefEntity> refs = new ArrayList<>();
        for (String fieldRef : parseRefs(version.getFieldRefsJson())) {
            refs.add(ref(tenantId, "DECISION_VERSION", version.getDecisionCode(), versionNumber(version),
                    version.getPid(), "FIELD", null, fieldRef, "VERSION",
                    metadata("status", version.getStatus(), "kind", version.getKind(),
                            "runtimeAdapter", version.getRuntimeAdapter())));
        }
        for (String functionRef : parseRefs(version.getFunctionRefsJson())) {
            refs.add(ref(tenantId, "DECISION_VERSION", version.getDecisionCode(), versionNumber(version),
                    version.getPid(), "FUNCTION", functionRef, null, "VERSION",
                    metadata("status", version.getStatus(), "kind", version.getKind(),
                            "runtimeAdapter", version.getRuntimeAdapter())));
        }
        Set<String> subDecisionRefs = new LinkedHashSet<>();
        collectDecisionRefs(version.getContentJson(), subDecisionRefs);
        for (String subDecision : subDecisionRefs) {
            refs.add(ref(tenantId, "DECISION_VERSION", version.getDecisionCode(), versionNumber(version),
                    version.getPid(), "DECISION", subDecision, null, "VERSION",
                    metadata("status", version.getStatus(), "kind", version.getKind(),
                            "runtimeAdapter", version.getRuntimeAdapter())));
        }
        return refs;
    }

    private List<DecisionUsageRefEntity> scanAutomations(Long tenantId) {
        return automationMapper.selectList(new LambdaQueryWrapper<Automation>()
                        .eq(Automation::getTenantId, tenantId)
                        .eq(Automation::getDeletedFlag, false))
                .stream()
                .flatMap(automation -> refsForAutomation(tenantId, automation).stream())
                .toList();
    }

    private List<DecisionUsageRefEntity> refsForAutomation(Long tenantId, Automation automation) {
        List<DecisionUsageRefEntity> refs = new ArrayList<>();
        TriggerConfig config = automation.getTriggerConfig();
        if (config != null && !nullToBlank(config.getDecisionRef()).isBlank()) {
            refs.add(ref(tenantId, "AUTOMATION", automation.getPid(), null, automation.getPid(),
                    "DECISION", config.getDecisionRef(), null, defaultBinding(config.getDecisionBinding()),
                    metadata("sourceName", automation.getName(), "modelCode", automation.getModelCode(),
                            "triggerType", automation.getTriggerType(), "enabled", automation.getEnabled())));
        }
        refs.addAll(refsForAutomationActions(tenantId, automation));
        return refs;
    }

    private List<DecisionUsageRefEntity> refsForAutomationActions(Long tenantId, Automation automation) {
        if (automation.getActions() == null || automation.getActions().isEmpty()) {
            return List.of();
        }
        List<DecisionUsageRefEntity> refs = new ArrayList<>();
        for (AutomationAction action : automation.getActions()) {
            if (action == null || action.getConfig() == null) {
                continue;
            }
            String actionType = nullToBlank(action.getType()).trim().toLowerCase(Locale.ROOT);
            Map<String, Object> config = action.getConfig();
            if ("call_api".equals(actionType)) {
                String connectorPid = firstString(config, "connectorPid", "apiConnectorPid", "connectorCode");
                if (!connectorPid.isBlank()) {
                    refs.add(ref(tenantId, "AUTOMATION", automation.getPid(), null, automation.getPid(),
                            "CONNECTOR", connectorPid,
                            firstString(config, "endpointCode", "endpoint", "operation"),
                            "ACTION",
                            integrationActionMetadata(automation, action, config)));
                }
            } else if ("send_webhook".equals(actionType)) {
                String webhookPid = firstString(config, "webhookPid", "webhookSubscriptionPid", "subscriptionPid");
                if (!webhookPid.isBlank()) {
                    refs.add(ref(tenantId, "AUTOMATION", automation.getPid(), null, automation.getPid(),
                            "WEBHOOK", webhookPid,
                            firstString(config, "eventType", "event", "topic"),
                            "ACTION",
                            integrationActionMetadata(automation, action, config)));
                }
            }
        }
        return refs;
    }

    private List<DecisionUsageRefEntity> scanSlaRules(Long tenantId) {
        return slaConfigMapper.selectList(new LambdaQueryWrapper<SlaConfigEntity>()
                        .eq(SlaConfigEntity::getTenantId, tenantId)
                        .eq(SlaConfigEntity::getDeletedFlag, false))
                .stream()
                .flatMap(sla -> refsForSlaRule(tenantId, sla).stream())
                .toList();
    }

    private List<DecisionUsageRefEntity> refsForSlaRule(Long tenantId, SlaConfigEntity sla) {
        if (!"RULE".equalsIgnoreCase(nullToBlank(sla.getDeadlineMode()))
                || nullToBlank(sla.getDeadlineValue()).isBlank()) {
            return List.of();
        }
        return List.of(ref(tenantId, "SLA_RULE", sla.getPid(), null, sla.getPid(),
                "DECISION", sla.getDeadlineValue(), null, "LATEST",
                metadata("sourceName", sla.getName(), "targetType", sla.getTargetType(),
                        "targetKey", sla.getTargetKey(), "enabled", sla.getEnabled())));
    }

    private List<DecisionUsageRefEntity> scanEventPolicies(Long tenantId) {
        List<DrtPolicyVersionEntity> versions = policyVersionMapper.selectList(
                new LambdaQueryWrapper<DrtPolicyVersionEntity>()
                        .eq(DrtPolicyVersionEntity::getTenantId, tenantId)
                        .in(DrtPolicyVersionEntity::getStatus, INDEXABLE_VERSION_STATUSES));

        return versions.stream()
                .flatMap(version -> refsForEventPolicyVersion(tenantId, version).stream())
                .toList();
    }

    private List<DecisionUsageRefEntity> refsForEventPolicyVersion(Long tenantId, DrtPolicyVersionEntity version) {
        Set<String> decisionRefs = new LinkedHashSet<>();
        collectDecisionRefs(version.getRulesJson(), decisionRefs);
        Set<String> webhookEventTypes = new LinkedHashSet<>();
        collectWebhookEventTypes(version.getRulesJson(), webhookEventTypes);
        if (decisionRefs.isEmpty() && webhookEventTypes.isEmpty()) {
            return List.of();
        }
        DrtPolicyDefinitionEntity def = policyDefinitionMapper.findByTenantAndCode(tenantId, version.getPolicyCode());
        String sourceName = def != null ? def.getPolicyName() : version.getPolicyCode();
        List<DecisionUsageRefEntity> refs = new ArrayList<>();
        for (String decisionRef : decisionRefs) {
            refs.add(ref(tenantId, "EVENT_POLICY", version.getPolicyCode(), versionNumber(version),
                    version.getPid(), "DECISION", decisionRef, null, "VERSION_RULES",
                    metadata("sourceName", sourceName, "status", version.getStatus(), "phase", version.getPhase(),
                            "matchMode", version.getMatchMode())));
        }
        for (String eventType : webhookEventTypes) {
            refs.add(ref(tenantId, "EVENT_POLICY", version.getPolicyCode(), versionNumber(version),
                    version.getPid(), "WEBHOOK", eventType, eventType, "VERSION_RULES",
                    metadata("sourceName", sourceName, "status", version.getStatus(), "phase", version.getPhase(),
                            "matchMode", version.getMatchMode(), "actionType", "WEBHOOK")));
        }
        return refs;
    }

    private List<DecisionUsageRefEntity> scanNamedQueries(Long tenantId) {
        List<NamedQuery> queries = namedQueryMapper.selectList(new LambdaQueryWrapper<NamedQuery>()
                .eq(NamedQuery::getTenantId, tenantId));
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        return queries.stream()
                .flatMap(query -> refsForNamedQuery(tenantId, query).stream())
                .toList();
    }

    private List<DecisionUsageRefEntity> refsForNamedQuery(Long tenantId, NamedQuery query) {
        if (query == null
                || "archived".equalsIgnoreCase(nullToBlank(query.getStatus()))
                || nullToBlank(query.getConnectorPid()).isBlank()) {
            return List.of();
        }
        return List.of(ref(tenantId, "NAMED_QUERY", query.getCode(), null, query.getPid(),
                "CONNECTOR", query.getConnectorPid(), query.getConnectorEndpointCode(), "QUERY_SOURCE",
                metadata("sourceName", query.getTitle(), "status", query.getStatus())));
    }

    private DecisionUsageRefEntity ref(Long tenantId, String sourceType, String sourceCode, String sourceVersion,
                                       String sourcePid, String targetType, String targetCode, String targetPath,
                                       String binding, Map<String, Object> metadata) {
        Instant now = Instant.now();
        DecisionUsageRefEntity ref = new DecisionUsageRefEntity();
        ref.setPid(UniqueIdGenerator.generate());
        ref.setTenantId(tenantId);
        ref.setSourceType(sourceType);
        ref.setSourceCode(sourceCode);
        ref.setSourceVersion(sourceVersion);
        ref.setSourcePid(sourcePid);
        ref.setTargetType(targetType);
        ref.setTargetCode(targetCode);
        ref.setTargetPath(targetPath);
        ref.setBinding(binding);
        ref.setMetadataJson(objectMapper.valueToTree(metadata));
        ref.setCreatedAt(now);
        ref.setUpdatedAt(now);
        return ref;
    }

    private DecisionImpactRefDTO toDto(DecisionUsageRefEntity entity) {
        Map<String, Object> metadata = metadataFrom(entity.getMetadataJson());
        DecisionImpactRefDTO dto = new DecisionImpactRefDTO();
        dto.setSourceType(entity.getSourceType());
        dto.setSourceCode(entity.getSourceCode());
        dto.setSourceName(metadata.get("sourceName") instanceof String sourceName ? sourceName : null);
        dto.setSourceVersion(entity.getSourceVersion());
        dto.setSourcePid(entity.getSourcePid());
        dto.setTargetType(entity.getTargetType());
        dto.setTargetCode(entity.getTargetCode());
        dto.setTargetPath(entity.getTargetPath());
        dto.setBinding(entity.getBinding());
        dto.setMetadata(metadata);
        return dto;
    }

    private DecisionUsageIndexRebuildDTO insertRefs(Long tenantId, List<DecisionUsageRefEntity> refs) {
        Set<String> seen = new LinkedHashSet<>();
        DecisionUsageIndexRebuildDTO summary = newSummary(tenantId);

        for (DecisionUsageRefEntity ref : refs) {
            String key = key(ref);
            if (!seen.add(key)) {
                continue;
            }
            usageRefMapper.insert(ref);
            increment(summary, ref);
        }

        return summary;
    }

    private DecisionUsageIndexRebuildDTO newSummary(Long tenantId) {
        DecisionUsageIndexRebuildDTO summary = new DecisionUsageIndexRebuildDTO();
        summary.setTenantId(tenantId);
        summary.setTotalRefs(0);
        summary.setConsumerRefs(0);
        summary.setDecisionRefs(0);
        summary.setFieldRefs(0);
        summary.setFunctionRefs(0);
        summary.setIntegrationRefs(0);
        return summary;
    }

    private Map<String, Object> metadataFrom(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private void increment(DecisionUsageIndexRebuildDTO summary, DecisionUsageRefEntity ref) {
        summary.setTotalRefs(summary.getTotalRefs() + 1);
        if ("FIELD".equals(ref.getTargetType())) {
            summary.setFieldRefs(summary.getFieldRefs() + 1);
        } else if ("FUNCTION".equals(ref.getTargetType())) {
            summary.setFunctionRefs(summary.getFunctionRefs() + 1);
        } else if ("CONNECTOR".equals(ref.getTargetType()) || "WEBHOOK".equals(ref.getTargetType())) {
            summary.setIntegrationRefs(summary.getIntegrationRefs() + 1);
        } else if ("DECISION".equals(ref.getTargetType())) {
            if ("DECISION_VERSION".equals(ref.getSourceType())) {
                summary.setDecisionRefs(summary.getDecisionRefs() + 1);
            } else {
                summary.setConsumerRefs(summary.getConsumerRefs() + 1);
            }
        }
    }

    private String key(DecisionUsageRefEntity ref) {
        return String.join("\u001F",
                nullToBlank(ref.getSourceType()),
                nullToBlank(ref.getSourceCode()),
                nullToBlank(ref.getSourceVersion()),
                nullToBlank(ref.getSourcePid()),
                nullToBlank(ref.getTargetType()),
                nullToBlank(ref.getTargetCode()),
                nullToBlank(ref.getTargetPath()),
                nullToBlank(ref.getBinding()));
    }

    private List<String> parseRefs(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                refs.add(item.asText());
            }
        }
        return refs;
    }

    private void collectDecisionRefs(JsonNode node, Set<String> refs) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode decisionRef = node.get("decisionRef");
            if (decisionRef != null && decisionRef.isTextual() && !decisionRef.asText().isBlank()) {
                refs.add(decisionRef.asText());
            }
            node.properties().forEach(entry -> collectDecisionRefs(entry.getValue(), refs));
        } else if (node.isArray()) {
            node.forEach(child -> collectDecisionRefs(child, refs));
        }
    }

    private void collectWebhookEventTypes(JsonNode node, Set<String> refs) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode type = node.get("type");
            if (type != null && type.isTextual() && "WEBHOOK".equalsIgnoreCase(type.asText())) {
                String eventType = jsonText(node.path("payload").path("eventType"));
                if (eventType.isBlank()) {
                    eventType = jsonText(node.get("eventType"));
                }
                if (!eventType.isBlank()) {
                    refs.add(eventType);
                }
            }
            node.properties().forEach(entry -> collectWebhookEventTypes(entry.getValue(), refs));
        } else if (node.isArray()) {
            node.forEach(child -> collectWebhookEventTypes(child, refs));
        }
    }

    private Map<String, Object> metadata(Object... kvs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            if (kvs[i] != null && kvs[i + 1] != null) {
                map.put(String.valueOf(kvs[i]), kvs[i + 1]);
            }
        }
        return map;
    }

    private Map<String, Object> integrationActionMetadata(Automation automation, AutomationAction action,
                                                          Map<String, Object> config) {
        return metadata("sourceName", automation.getName(),
                "modelCode", automation.getModelCode(),
                "triggerType", automation.getTriggerType(),
                "enabled", automation.getEnabled(),
                "actionType", action.getType(),
                "actionLabel", action.getLabel(),
                "actionSequence", action.getSequence(),
                "url", firstString(config, "url", "targetUrl"));
    }

    private String firstString(Map<String, Object> map, String... keys) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private String jsonText(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private String versionNumber(DrtVersionEntity version) {
        return version.getVersion() == null ? null : String.valueOf(version.getVersion());
    }

    private String versionNumber(DrtPolicyVersionEntity version) {
        return version.getVersion() == null ? null : String.valueOf(version.getVersion());
    }

    private String defaultBinding(String binding) {
        return binding == null || binding.isBlank() ? "LATEST" : binding;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String normalizeSourceType(String sourceType) {
        String normalized = nullToBlank(sourceType).trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_SOURCE_TYPES.contains(normalized)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Unsupported usage-index source type: " + sourceType);
        }
        return normalized;
    }

    private String normalizeTargetType(String targetType) {
        String normalized = nullToBlank(targetType).trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Usage-index target type is required");
        }
        return normalized;
    }

    private Long requireTenant() {
        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tenantId == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Decision usage index not found");
        }
        return tenantId;
    }
}
