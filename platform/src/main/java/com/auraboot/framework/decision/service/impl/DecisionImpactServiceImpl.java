package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.decision.dto.DecisionFieldImpactDTO;
import com.auraboot.framework.decision.dto.DecisionFieldPreflightDTO;
import com.auraboot.framework.decision.dto.DecisionFieldPreflightRequest;
import com.auraboot.framework.decision.dto.DecisionImpactDTO;
import com.auraboot.framework.decision.dto.DecisionImpactRefDTO;
import com.auraboot.framework.decision.dto.DecisionImpactRiskDTO;
import com.auraboot.framework.decision.dto.DecisionIntegrationImpactDTO;
import com.auraboot.framework.decision.dto.DecisionUsageIndexRebuildDTO;
import com.auraboot.framework.decision.service.DecisionImpactAckService;
import com.auraboot.framework.decision.service.DecisionImpactService;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import com.auraboot.framework.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Usage-index backed impact read model for DecisionOps governance.
 */
@Service
@RequiredArgsConstructor
public class DecisionImpactServiceImpl implements DecisionImpactService {

    private static final Set<String> SUPPORTED_FIELD_CHANGE_ACTIONS = Set.of(
            "DELETE_FIELD",
            "CHANGE_TYPE",
            "CHANGE_DATA_TYPE",
            "DELETE_DICT_ITEM",
            "CHANGE_PERMISSION",
            "CHANGE_VIRTUAL_SOURCE");

    private final DecisionUsageIndexService usageIndexService;
    private final DecisionImpactAckService impactAckService;

    @Override
    public DecisionImpactDTO getDecisionImpact(String decisionCode) {
        List<DecisionImpactRefDTO> incoming = usageIndexService.findIncomingDecisionRefs(decisionCode);
        List<DecisionImpactRefDTO> outgoing = usageIndexService.findOutgoingDecisionRefs(decisionCode);
        if (incoming.isEmpty()) {
            usageIndexService.rebuild();
            incoming = usageIndexService.findIncomingDecisionRefs(decisionCode);
            outgoing = usageIndexService.findOutgoingDecisionRefs(decisionCode);
        }

        DecisionImpactDTO dto = new DecisionImpactDTO();
        dto.setDecisionCode(decisionCode);
        dto.setIncoming(incoming);
        dto.setOutgoing(outgoing);
        dto.setRisk(buildRisk(incoming, "No downstream consumers"));
        return dto;
    }

    @Override
    public DecisionFieldImpactDTO getFieldImpact(String fieldRef) {
        List<DecisionImpactRefDTO> refs = usageIndexService.findFieldRefs(fieldRef);
        if (refs.isEmpty()) {
            usageIndexService.rebuild();
            refs = usageIndexService.findFieldRefs(fieldRef);
        }

        DecisionFieldImpactDTO dto = new DecisionFieldImpactDTO();
        dto.setFieldRef(fieldRef);
        dto.setReferences(refs);
        dto.setRisk(buildRisk(refs, "No field consumers"));
        return dto;
    }

    @Override
    public DecisionFieldPreflightDTO preflightFieldChange(DecisionFieldPreflightRequest request) {
        String action = normalizeFieldChangeAction(request.getAction());
        DecisionFieldImpactDTO impact = getFieldImpact(request.getFieldRef());
        boolean noOpTypeChange = "CHANGE_DATA_TYPE".equals(action)
                && sameDataType(request.getCurrentDataType(), request.getNextDataType());
        DecisionImpactRiskDTO risk = noOpTypeChange
                ? nonBlockingRisk("No schema type change detected")
                : impact.getRisk();
        boolean requiresAcknowledgement = Boolean.TRUE.equals(risk.getBlocking());
        boolean acknowledged = Boolean.TRUE.equals(request.getImpactAcknowledged());
        boolean blocked = requiresAcknowledgement && !acknowledged;

        DecisionFieldPreflightDTO dto = new DecisionFieldPreflightDTO();
        dto.setFieldRef(request.getFieldRef());
        dto.setAction(action);
        dto.setCurrentDataType(request.getCurrentDataType());
        dto.setNextDataType(request.getNextDataType());
        dto.setDictCode(request.getDictCode());
        dto.setDictValue(request.getDictValue());
        dto.setNextPermission(request.getNextPermission());
        dto.setNextSourceRef(request.getNextSourceRef());
        dto.setReferences(impact.getReferences());
        dto.setRisk(risk);
        dto.setRequiresAcknowledgement(requiresAcknowledgement);
        dto.setBlocked(blocked);
        dto.setAllowed(!blocked);
        dto.setMessage(preflightMessage(blocked, requiresAcknowledgement, risk));
        if (requiresAcknowledgement && acknowledged && !blocked) {
            impactAckService.recordAcknowledgement(
                    acknowledgementActionType(action),
                    "FIELD",
                    null,
                    null,
                    request.getFieldRef(),
                    risk.getSummary(),
                    dto,
                    request.getNote());
        }
        return dto;
    }

    @Override
    public DecisionIntegrationImpactDTO getIntegrationImpact(String targetType, String targetCode) {
        String normalizedTargetType = normalizeTargetType(targetType);
        List<DecisionImpactRefDTO> refs = usageIndexService.findTargetRefs(normalizedTargetType, targetCode);

        DecisionIntegrationImpactDTO dto = new DecisionIntegrationImpactDTO();
        dto.setTargetType(normalizedTargetType);
        dto.setTargetCode(targetCode);
        dto.setManageUrl(manageUrl(normalizedTargetType));
        dto.setReferences(refs);
        dto.setRisk(buildRisk(refs, "No integration consumers"));
        return dto;
    }

    @Override
    public DecisionUsageIndexRebuildDTO rebuildUsageIndex() {
        return usageIndexService.rebuild();
    }

    private String normalizeTargetType(String targetType) {
        String normalized = targetType == null ? "" : targetType.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CONNECTOR", "WEBHOOK").contains(normalized)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Unsupported integration target type: " + targetType);
        }
        return normalized;
    }

    private String manageUrl(String targetType) {
        return switch (targetType) {
            case "CONNECTOR" -> "/p/api_connector";
            case "WEBHOOK" -> "/p/webhook";
            default -> null;
        };
    }

    private String normalizeFieldChangeAction(String action) {
        String normalized = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_FIELD_CHANGE_ACTIONS.contains(normalized)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Unsupported field preflight action: " + action);
        }
        return "CHANGE_TYPE".equals(normalized) ? "CHANGE_DATA_TYPE" : normalized;
    }

    private String acknowledgementActionType(String action) {
        return switch (action) {
            case "DELETE_FIELD" -> "FIELD_DELETE";
            case "CHANGE_DATA_TYPE" -> "FIELD_TYPE_CHANGE";
            case "DELETE_DICT_ITEM" -> "FIELD_DICT_ITEM_DELETE";
            case "CHANGE_PERMISSION" -> "FIELD_PERMISSION_CHANGE";
            case "CHANGE_VIRTUAL_SOURCE" -> "FIELD_VIRTUAL_SOURCE_CHANGE";
            default -> "FIELD_CHANGE";
        };
    }

    private boolean sameDataType(String left, String right) {
        return normalizeDataType(left).equals(normalizeDataType(right));
    }

    private String normalizeDataType(String dataType) {
        return dataType == null ? "" : dataType.trim().toLowerCase(Locale.ROOT);
    }

    private DecisionImpactRiskDTO nonBlockingRisk(String summary) {
        DecisionImpactRiskDTO risk = new DecisionImpactRiskDTO();
        risk.setBlocking(false);
        risk.setCounts(Map.of());
        risk.setSummary(summary);
        return risk;
    }

    private String preflightMessage(boolean blocked, boolean requiresAcknowledgement, DecisionImpactRiskDTO risk) {
        if (blocked) {
            return "Field change requires impact acknowledgement: " + risk.getSummary();
        }
        if (requiresAcknowledgement) {
            return "Field change allowed after impact acknowledgement: " + risk.getSummary();
        }
        return "Field change allowed";
    }

    private DecisionImpactRiskDTO buildRisk(List<DecisionImpactRefDTO> refs, String emptySummary) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DecisionImpactRefDTO ref : refs) {
            counts.merge(ref.getSourceType(), 1, Integer::sum);
        }

        DecisionImpactRiskDTO risk = new DecisionImpactRiskDTO();
        risk.setBlocking(!refs.isEmpty());
        risk.setCounts(counts);
        risk.setSummary(summary(counts, emptySummary));
        return risk;
    }

    private String summary(Map<String, Integer> counts, String emptySummary) {
        if (counts.isEmpty()) {
            return emptySummary;
        }
        List<String> parts = new ArrayList<>();
        addSummaryPart(parts, counts, "AUTOMATION", "automation", "automations");
        addSummaryPart(parts, counts, "SLA_RULE", "SLA rule", "SLA rules");
        addSummaryPart(parts, counts, "EVENT_POLICY", "EventPolicy", "EventPolicies");
        addSummaryPart(parts, counts, "DECISION_VERSION", "decision version", "decision versions");
        addSummaryPart(parts, counts, "NAMED_QUERY", "NamedQuery", "NamedQueries");
        if (parts.isEmpty()) {
            counts.forEach((key, count) -> {
                if (count != null && count > 0) {
                    parts.add(count + " " + key);
                }
            });
        }
        return "Used by " + String.join(" + ", parts);
    }

    private void addSummaryPart(List<String> parts, Map<String, Integer> counts, String key,
                                String singular, String plural) {
        Integer count = counts.get(key);
        if (count != null && count > 0) {
            parts.add(count + " " + (count == 1 ? singular : plural));
        }
    }
}
