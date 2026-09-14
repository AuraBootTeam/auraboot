package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.exception.CasVersionConflictException;
import com.auraboot.framework.openplatform.service.OpenApiPreconditionException;
import com.auraboot.framework.openplatform.service.OpenApiProtocolTokenCodec;
import com.auraboot.framework.openplatform.service.OpenApiEventCatalog;
import com.auraboot.framework.openplatform.service.OpenApiEventPublisher;
import com.auraboot.framework.openplatform.service.OpenApiPublicationRegistry;
import com.auraboot.framework.openplatform.service.OpenApiPublicationRegistry.CommandPublication;
import com.auraboot.framework.openplatform.service.OpenApiPublicationRegistry.ResourcePublication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/open/v1")
@AuthenticatedAccess
public class OpenPlatformFacadeController {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,160}");

    private final OpenApiPublicationRegistry publications;
    private final DynamicDataService dataService;
    private final CommandExecutor commandExecutor;
    private final OpenApiProtocolTokenCodec tokenCodec;
    private final OpenApiEventCatalog eventCatalog;
    private final OpenApiEventPublisher eventPublisher;

    public OpenPlatformFacadeController(OpenApiPublicationRegistry publications,
                                        DynamicDataService dataService,
                                        CommandExecutor commandExecutor,
                                        OpenApiProtocolTokenCodec tokenCodec,
                                        OpenApiEventCatalog eventCatalog,
                                        OpenApiEventPublisher eventPublisher) {
        this.publications = publications;
        this.dataService = dataService;
        this.commandExecutor = commandExecutor;
        this.tokenCodec = tokenCodec;
        this.eventCatalog = eventCatalog;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping("/event-catalog")
    @Operation(summary = "List externally published event contracts")
    public List<OpenApiEventCatalog.EventDescriptor> listEventCatalog() {
        return eventCatalog.externallyPublished();
    }

    @GetMapping("/resources/{resourceCode}")
    @Operation(summary = "List an explicitly published resource",
            description = "Uses authenticated opaque keyset cursors bound to the resource schema.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Projected resource page"),
            @ApiResponse(responseCode = "400", description = "Invalid, expired or mismatched cursor"),
            @ApiResponse(responseCode = "404", description = "Resource is not published")
    })
    public Map<String, Object> listResources(@PathVariable String resourceCode,
                                             @RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(required = false) String cursor) {
        ResourcePublication publication = publications.resource(resourceCode)
                .orElseThrow(() -> new IllegalArgumentException("Resource is not published"));
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        // Always use keyset mode so the first and subsequent pages share pid ASC ordering.
        String internalCursor = cursor == null || cursor.isBlank() ? ""
                : tokenCodec.decodeCursor(cursor, publication.code(), publication.schemaVersion());
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1).pageSize(boundedLimit + 1).cursor(internalCursor).build();
        PaginationResult<Map<String, Object>> page = MetaContext.runWithCommandPermitScope("ALL",
                () -> dataService.list(publication.modelCode(), request));
        List<Map<String, Object>> source = page.getRecords() == null ? List.of() : page.getRecords();
        boolean hasMore = source.size() > boundedLimit;
        List<Map<String, Object>> selected = source.stream().limit(boundedLimit).toList();
        String nextCursor = null;
        if (hasMore && !selected.isEmpty()) {
            Object lastPid = selected.get(selected.size() - 1).get("pid");
            if (lastPid == null || String.valueOf(lastPid).isBlank()) {
                throw new IllegalStateException("Published resource continuation is unavailable");
            }
            nextCursor = tokenCodec.encodeCursor(publication.code(), publication.schemaVersion(),
                    String.valueOf(lastPid));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", selected.stream().map(item -> publications.project(publication, item)).toList());
        response.put("hasMore", hasMore);
        response.put("nextCursor", nextCursor);
        return response;
    }

    @GetMapping("/resources/{resourceCode}/{recordPid}")
    @Operation(summary = "Get an explicitly published resource")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Projected resource",
                    headers = @Header(name = "ETag",
                            description = "Strong resource and row-version bound ETag")),
            @ApiResponse(responseCode = "404", description = "Resource or record is not available")
    })
    public ResponseEntity<Map<String, Object>> getResource(@PathVariable String resourceCode,
                                                            @PathVariable String recordPid) {
        ResourcePublication publication = publications.resource(resourceCode)
                .orElseThrow(() -> new IllegalArgumentException("Resource is not published"));
        Map<String, Object> record = MetaContext.runWithCommandPermitScope("ALL",
                () -> dataService.getById(publication.modelCode(), recordPid));
        return ResponseEntity.ok()
                .eTag(tokenCodec.encodeEtag(publication.code(), recordPid, publications.rowVersion(record)))
                .body(publications.project(publication, record));
    }

    @PostMapping("/commands/{commandCode}:execute")
    @Transactional
    @Operation(summary = "Execute an explicitly published conditional command")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Command result and new ETag"),
            @ApiResponse(responseCode = "400", description = "Invalid command request"),
            @ApiResponse(responseCode = "404", description = "Command or target is not available"),
            @ApiResponse(responseCode = "412", description = "If-Match is missing, invalid or stale")
    })
    public Map<String, Object> executeCommand(@PathVariable String commandCode,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @Parameter(required = true,
                                                      description = "Strong ETag from the latest resource GET")
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
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
        long expectedVersion = tokenCodec.decodeEtag(ifMatch, publication.resultResourceCode(), String.valueOf(target));
        request.setExpectedVersion(Math.toIntExact(expectedVersion));
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
        Map<String, Object> publicResource = publications.project(resultResource, record);
        response.put("resource", publicResource);
        response.put("etag", tokenCodec.encodeEtag(resultResource.code(), String.valueOf(target),
                publications.rowVersion(record)));
        if (!result.isIdempotentReplay()) {
            eventPublisher.publish(publication.eventType(), publication.eventVersion(), String.valueOf(target),
                    publicResource, principal.tenantId());
        }
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

    @ExceptionHandler({OpenApiPreconditionException.class, CasVersionConflictException.class})
    public ResponseEntity<Map<String, Object>> preconditionFailed(RuntimeException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "precondition_failed");
        body.put("message", exception.getMessage());
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(body);
    }
}
