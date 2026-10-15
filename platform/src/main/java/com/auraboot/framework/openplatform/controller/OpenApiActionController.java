package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.openplatform.service.OpenApiActionService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/open/v1/actions")
@AuthenticatedAccess("Business API scope and bound resource authority are checked by the service")
@RequiredArgsConstructor
public class OpenApiActionController {
    private final OpenApiActionService actions;
    private final ObjectMapper json;
    @PostMapping("/{code}:execute")
    public ResponseEntity<Map<String, Object>> execute(@AuthenticationPrincipal MachinePrincipal principal,
            @PathVariable String code, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestBody JsonNode request) {
        if (!request.isObject() || !request.path("input").isObject()) throw new IllegalArgumentException("Business API input object required");
        request.fieldNames().forEachRemaining(field -> {
            if (!Set.of("targetPid", "input").contains(field)) throw new IllegalArgumentException("Unknown business API request property");
        });
        if (request.has("targetPid") && !request.get("targetPid").isTextual()) throw new IllegalArgumentException("String target PID required");
        return actions.execute(principal, code, key, ifMatch, request.has("targetPid") ? request.get("targetPid").asText() : null,
                json.convertValue(request.get("input"), new TypeReference<Map<String, Object>>() { })).response();
    }
}
