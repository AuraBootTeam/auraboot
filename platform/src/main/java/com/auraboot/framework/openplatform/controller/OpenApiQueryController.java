package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.openplatform.service.OpenApiQueryService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/open/v1/queries")
@AuthenticatedAccess("Published query scopes and installation/source policies are checked by the service")
@RequiredArgsConstructor
public class OpenApiQueryController {
    private final OpenApiQueryService queries;
    private final ObjectMapper json;
    @PostMapping("/{code}:execute")
    public Map<String, Object> execute(@AuthenticationPrincipal MachinePrincipal principal, @PathVariable String code,
                                       @RequestBody JsonNode request) {
        if (!request.isObject()) throw new IllegalArgumentException("Public query object required");
        request.fieldNames().forEachRemaining(key -> {
            if (!Set.of("parameters", "limit", "cursor").contains(key)) throw new IllegalArgumentException("Unknown public query request field");
        });
        if (request.has("parameters") && !request.get("parameters").isObject()) throw new IllegalArgumentException("Public query parameter object required");
        if (request.has("limit") && (!request.get("limit").isIntegralNumber() || !request.get("limit").canConvertToInt()))
            throw new IllegalArgumentException("Integer query limit required");
        if (request.has("cursor") && !request.get("cursor").isTextual()) throw new IllegalArgumentException("String query cursor required");
        Map<String, Object> parameters = request.has("parameters") ? json.convertValue(request.get("parameters"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }) : Map.of();
        return queries.execute(principal, code, new OpenApiQueryService.ExecuteRequest(parameters,
                request.has("limit") ? request.get("limit").intValue() : null, request.has("cursor") ? request.get("cursor").asText() : null));
    }
}
