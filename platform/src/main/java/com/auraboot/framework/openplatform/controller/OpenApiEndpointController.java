package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.openplatform.service.OpenApiEndpointService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/open/v1/endpoints")
@AuthenticatedAccess("Endpoint, current installation and delegated data authority are checked in the service")
@RequiredArgsConstructor
public class OpenApiEndpointController {
    private final OpenApiEndpointService endpoints;
    @RequestMapping(value = {"/{code}", "/{code}/{recordPid}"}, method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<Map<String, Object>> invoke(@AuthenticationPrincipal MachinePrincipal principal, @PathVariable String code,
            @PathVariable(required = false) String recordPid, @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, HttpServletRequest request) {
        return endpoints.invoke(principal, code, request.getMethod(), recordPid, query, body, key, ifMatch).response();
    }
}
