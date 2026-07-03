package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.dto.DecisionActionCatalogDTO;
import com.auraboot.framework.decision.dto.DecisionActionDTO;
import com.auraboot.framework.decision.service.DecisionActionCatalogService;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DecisionActionCatalogServiceImpl implements DecisionActionCatalogService {

    private final ObjectProvider<ActionHandler> actionHandlers;

    @Override
    public DecisionActionCatalogDTO getActionCatalog() {
        DecisionActionCatalogDTO catalog = new DecisionActionCatalogDTO();
        catalog.setActions(ACTION_DEFINITIONS.stream()
                .map(this::toDTO)
                .toList());
        return catalog;
    }

    private DecisionActionDTO toDTO(ActionDefinition definition) {
        DecisionActionDTO dto = new DecisionActionDTO();
        dto.setActionType(definition.actionType());
        dto.setLabel(definition.label());
        dto.setCategory(definition.category());
        dto.setDescription(definition.description());
        dto.setScopes(definition.scopes());
        dto.setHandlerAvailable(handlerAvailable(definition.actionType()));
        dto.setInputSchema(definition.inputSchema());
        return dto;
    }

    private boolean handlerAvailable(String actionType) {
        return actionHandlers.stream().anyMatch(handler -> handler.supports(actionType));
    }

    private record ActionDefinition(
            String actionType,
            String label,
            String category,
            String description,
            List<String> scopes,
            Map<String, Object> inputSchema) {
    }

    private static final List<ActionDefinition> ACTION_DEFINITIONS = List.of(
            new ActionDefinition(
                    "NOTIFY",
                    "Send notification",
                    "messaging",
                    "Send an in-app notification to a resolved user target.",
                    List.of("actor", "record", "event"),
                    schema(
                            List.of("target", "payload.title", "payload.content"),
                            Map.of(
                                    "target", field("string", "USER:<userId>", true),
                                    "payload.title", field("string", "Notification title", true),
                                    "payload.content", field("text", "Notification content", true)))),
            new ActionDefinition(
                    "START_PROCESS",
                    "Start BPM process",
                    "workflow",
                    "Start a BPM process instance and pass policy context variables.",
                    List.of("record", "actor", "event"),
                    schema(
                            List.of("payload.processDefinitionId"),
                            Map.of(
                                    "payload.processDefinitionId", field("string", "Process definition id", true),
                                    "payload.businessKey", field("string", "Business key; defaults to recordPid", false),
                                    "payload.variables", field("object", "Process variables", false)))),
            new ActionDefinition(
                    "ADD_COMMENT",
                    "Add record comment",
                    "collaboration",
                    "Append a comment to the event record.",
                    List.of("record", "actor"),
                    schema(
                            List.of("payload.content"),
                            Map.of(
                                    "payload.content", field("text", "Comment content", true),
                                    "payload.mentions", field("string", "Mention expression", false)))),
            new ActionDefinition(
                    "UPDATE_RECORD",
                    "Update record",
                    "data",
                    "Update fields on the event record.",
                    List.of("record"),
                    schema(
                            List.of("payload.fields"),
                            Map.of("payload.fields", field("object", "Field values keyed by model field code", true)))),
            new ActionDefinition(
                    "PATCH_RECORD",
                    "Patch record",
                    "data",
                    "Patch fields on the event record.",
                    List.of("record"),
                    schema(
                            List.of("payload.fields"),
                            Map.of("payload.fields", field("object", "Field values keyed by model field code", true)))),
            new ActionDefinition(
                    "WEBHOOK",
                    "Dispatch webhook",
                    "integration",
                    "Dispatch a tenant webhook event through the platform webhook subsystem.",
                    List.of("event", "record", "tenant"),
                    schema(
                            List.of("payload.eventType"),
                            Map.of(
                                    "payload.eventType", field("string", "Webhook event type", true),
                                    "payload", field("object", "Webhook body fields", false)))),
            new ActionDefinition(
                    "WRITE_AUDIT",
                    "Write audit",
                    "governance",
                    "Write a business audit entry for the matched policy action.",
                    List.of("record", "event", "tenant"),
                    schema(
                            List.of(),
                            Map.of(
                                    "payload.message", field("text", "Audit message", false),
                                    "payload", field("object", "Audit payload", false)))));

    private static Map<String, Object> schema(List<String> required, Map<String, Object> fields) {
        return Map.of(
                "type", "object",
                "required", required,
                "fields", fields);
    }

    private static Map<String, Object> field(String dataType, String label, boolean required) {
        return Map.of(
                "dataType", dataType,
                "label", label,
                "required", required);
    }
}
