package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.openplatform.service.OpenApiResourceContract.Operation;
import com.auraboot.framework.openplatform.service.OpenApiResourceService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/open/v1/resources")
@AuthenticatedAccess
@RequiredArgsConstructor
public class OpenApiResourceWriteController {
    private final OpenApiResourceService resources;

    @PostMapping("/{code}")
    public ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal MachinePrincipal principal,
            @PathVariable String code, @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
        return resources.write(principal, code, Operation.CREATE, null, key, null, body).response();
    }
    @PatchMapping("/{code}/{pid}")
    public ResponseEntity<Map<String, Object>> update(@AuthenticationPrincipal MachinePrincipal principal,
            @PathVariable String code, @PathVariable String pid, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestBody Map<String, Object> body) {
        return resources.write(principal, code, Operation.UPDATE, pid, key, ifMatch, body).response();
    }
    @DeleteMapping("/{code}/{pid}")
    public ResponseEntity<Map<String, Object>> delete(@AuthenticationPrincipal MachinePrincipal principal,
            @PathVariable String code, @PathVariable String pid, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return resources.write(principal, code, Operation.DELETE, pid, key, ifMatch, null).response();
    }
}
