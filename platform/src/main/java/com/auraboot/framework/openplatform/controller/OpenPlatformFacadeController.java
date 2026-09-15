package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.openplatform.service.OpenApiPublicationRegistry;
import com.auraboot.framework.openplatform.service.OpenApiPublicationRegistry.CommandPublication;
import com.auraboot.framework.openplatform.service.OpenApiPublicationRegistry.ResourcePublication;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/open/v1")
@AuthenticatedAccess
public class OpenPlatformFacadeController {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,160}");

    private final OpenApiPublicationRegistry publications;
    private final DynamicDataService dataService;
    private final CommandExecutor commandExecutor;

    public OpenPlatformFacadeController(OpenApiPublicationRegistry publications,
                                        DynamicDataService dataService,
                                        CommandExecutor commandExecutor) {
        this.publications = publications;
        this.dataService = dataService;
        this.commandExecutor = commandExecutor;
    }

    @GetMapping("/resources/{resourceCode}/{recordPid}")
    public Map<String, Object> getResource(@PathVariable String resourceCode,
                                           @PathVariable String recordPid) {
        ResourcePublication publication = publications.resource(resourceCode)
                .orElseThrow(() -> new IllegalArgumentException("Resource is not published"));
        Map<String, Object> record = MetaContext.runWithCommandPermitScope("ALL",
                () -> dataService.getById(publication.modelCode(), recordPid));
        return publications.project(publication, record);
    }

    @PostMapping("/commands/{commandCode}:execute")
    public Map<String, Object> executeCommand(@PathVariable String commandCode,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @RequestBody Map<String, Object> body,
                                              @AuthenticationPrincipal MachinePrincipal principal) {
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("Invalid Idempotency-Key");
        }
        CommandPublication publication = publications.command(commandCode)
                .orElseThrow(() -> new IllegalArgumentException("Command is not published"));
        Object target = body.get("targetPid");
        Object input = body.get("input");
        if (target == null || String.valueOf(target).isBlank() || !(input instanceof Map<?, ?> rawInput)) {
            throw new IllegalArgumentException("targetPid and input are required");
        }
        Map<String, Object> externalInput = new LinkedHashMap<>();
        rawInput.forEach((key, value) -> externalInput.put(String.valueOf(key), value));
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordPid(String.valueOf(target));
        request.setPayload(publications.mapInput(publication, externalInput));
        request.setClientRequestId("open-api:" + principal.installationPid() + ":"
                + commandCode + ":" + idempotencyKey);
        request.setAuditContext(Map.of("channel", "open_api", "installationPid", principal.installationPid()));

        CommandExecuteResult result = MetaContext.runWithExternalCommandPermission(publication.permission(),
                () -> commandExecutor.execute(publication.commandCode(), request));
        ResourcePublication resultResource = publications.resource(publication.resultResourceCode())
                .orElseThrow(() -> new IllegalStateException("Published command result resource is missing"));
        Map<String, Object> record = MetaContext.runWithCommandPermitScope("ALL",
                () -> dataService.getById(resultResource.modelCode(), String.valueOf(target)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("command", publication.code());
        response.put("idempotentReplay", result.isIdempotentReplay());
        response.put("resource", publications.project(resultResource, record));
        return response;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalidRequest(IllegalArgumentException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "invalid_request");
        body.put("message", exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
