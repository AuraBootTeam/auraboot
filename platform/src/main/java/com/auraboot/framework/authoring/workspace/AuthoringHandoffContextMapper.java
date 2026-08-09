package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CreateHandoffRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.HandoffContextView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.HandoffRow;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.WorkspaceRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Maps the server-owned handoff payload without exposing it through the URL. */
@Component
public class AuthoringHandoffContextMapper {

    private final ObjectMapper objectMapper;

    public AuthoringHandoffContextMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode createPayload(WorkspaceRow workspace, CreateHandoffRequest request) {
        return objectMapper.valueToTree(Map.of(
                "pagePid", workspace.pagePid(),
                "changeSetPid", workspace.changeSetPid(),
                "sessionPid", workspace.sessionPid(),
                "revision", workspace.changeSetRevision(),
                "intent", request.intent().name(),
                "returnTo", returnTo(workspace.interactionContext()),
                "blockId", safeNullable(request.blockId()),
                "propertyPath", safeNullable(request.propertyPath()),
                "interactionContext", workspace.interactionContext()));
    }

    public HandoffContextView toView(HandoffRow handoff) {
        JsonNode payload = handoff.contextPayload();
        return new HandoffContextView(
                payload.path("pagePid").asText(), handoff.changeSetPid(),
                payload.path("sessionPid").asText(), payload.path("revision").asLong(),
                AuthoringWorkspaceContracts.StudioIntent.valueOf(payload.path("intent").asText()),
                handoff.targetRoute(), payload.path("returnTo").asText("/"),
                nullableText(payload, "blockId"), nullableText(payload, "propertyPath"),
                payload.path("interactionContext"), handoff.expiresAt());
    }

    public String nullableText(JsonNode object, String field) {
        String value = object.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private String returnTo(JsonNode interactionContext) {
        String route = interactionContext == null
                ? null
                : interactionContext.path("route").asText(null);
        return route == null || route.isBlank() ? "/" : route;
    }

    private String safeNullable(String value) {
        return value == null ? "" : value;
    }
}
