package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.openplatform.mapper.*;
import com.auraboot.framework.openplatform.security.OpenApiBusinessAudit;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

/** Installed business APIs share resource authority, optimistic locking and persistent replay protection. */
@Service
@RequiredArgsConstructor
public class OpenApiActionService {
    private final OpenApiActionPublicationService publications;
    private final OpenApiActionValidator validator;
    private final OpenApiResourceAuthorizer resources;
    private final OpenApiResourceService publicResources;
    private final PublicApiHandlerRegistry handlers;
    private final DynamicDataService data;
    private final CommandExecutor commands;
    private final OpenApiPublicationMapper mapper;
    private final OpenApiIdempotencyMapper idempotency;
    private final OpenApiProtocolTokenCodec codec;
    private final OpenApiProtocolHash protocolHash;
    private final ObjectMapper json;
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() { };

    @Transactional
    public OpenApiResourceService.Result execute(MachinePrincipal principal, String code, String key, String ifMatch,
                                                 String targetPid, Map<String, Object> input) {
        if (principal == null || principal.tenantId() == null || principal.installationPid() == null)
            throw new AccessDeniedException("Installed machine principal required");
        var authority = publications.authority(principal, code).orElseThrow(() -> {
            if (publications.active(principal.tenantId(), code).isEmpty()) return new ResponseStatusException(HttpStatus.NOT_FOUND, "open_api_action_not_published");
            return new AccessDeniedException("Current business API token, scope and grant required");
        });
        var contract = authority.contract(); authority.policy().validate(contract);
        if (!authority.policy().permits(contract) || !principal.scopes().contains(contract.scopes().get("execute")))
            throw new AccessDeniedException("Complete business API effect authority required");
        try { validator.validate(principal.tenantId(), contract); }
        catch (IllegalArgumentException | com.auraboot.framework.meta.exception.CommandNotFoundException changed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "open_api_action_source_changed", changed);
        }
        var parameters = contract.validateInput(input);
        boolean command = "command".equals(contract.sourceKind());
        var resource = resources.authorize(principal, contract.resourceCode(), command
                ? OpenApiResourceContract.Operation.UPDATE : OpenApiResourceContract.Operation.LIST);
        OpenApiBusinessAudit.attempt(contract.resourceCode(), "execute", null);
        if (command && (targetPid == null || !targetPid.matches("[A-Za-z0-9_-]{1,100}")
                || key == null || !key.matches("[A-Za-z0-9._:-]{8,128}"))) throw new IllegalArgumentException("Target PID and Idempotency-Key required");
        if (!command && (targetPid != null || ifMatch != null || key != null))
            throw new IllegalArgumentException("Read-only handlers have no target, If-Match or Idempotency-Key");
        boolean acquired = false;
        for (int slot = 0; slot < contract.maxConcurrent(); slot++) {
            if (mapper.tryQuerySlot(principal.tenantId(), "action:" + code, slot)) { acquired = true; break; }
        }
        if (!acquired) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "open_api_action_concurrency_exceeded");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(contract.timeoutMs());
        try {
            mapper.setStatementTimeout(contract.timeoutMs() + "ms");
            if (!command) {
                final boolean[] read = {false};
                var result = handlers.require(contract.sourceCode()).execute(parameters, limit -> {
                    if (read[0]) throw new IllegalArgumentException("Handler resource page budget exceeded");
                    read[0] = true; requireDeadline(deadline);
                    return publicResources.list(principal, contract.resourceCode(), limit, null, null, null, null);
                });
                requireDeadline(deadline);
                if (result == null || !result.keySet().equals(contract.outputFields().keySet()))
                    throw new IllegalStateException("Public handler result does not match its registered schema");
                Map<String, Object> projected = new LinkedHashMap<>();
                contract.outputFields().forEach((alias, type) -> projected.put(alias, OpenApiResourceContract.validateFieldValue(
                        new OpenApiResourceContract.Field(alias, type, true, false, true, false, Set.of(), false, null, Map.of(), null), result.get(alias))));
                OpenApiBusinessAudit.result(contract.resourceCode(), "execute", null, false);
                return new OpenApiResourceService.Result(200, projected, Map.of(), false);
            }
            return command(principal, authority, resource, key, ifMatch, targetPid, parameters, deadline);
        } catch (org.springframework.dao.QueryTimeoutException timeout) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "open_api_action_timeout", timeout);
        }
    }

    private OpenApiResourceService.Result command(MachinePrincipal principal, OpenApiActionPublicationService.Authority authority,
            OpenApiResourceAuthorizer.Authorization resource, String key, String ifMatch, String pid, Map<String, Object> parameters, long deadline) {
        var action = authority.contract(); var contract = resource.contract(); var policy = resource.policy();
        if (resource.sourceBinding().queryFingerprint() != null || !policy.writableFields().containsAll(action.inputFields().values())
                || !policy.readableFields().containsAll(action.effectFields())) throw new AccessDeniedException("Business API resource input or effect fields are not granted");
        Map<String, Object> publicInput = new LinkedHashMap<>(); action.inputFields().forEach((parameter, alias) -> publicInput.put(alias, parameters.get(parameter)));
        Map<String, Object> mapped = policy.mapWrite(contract, publicInput, false);
        for (var entry : publicInput.entrySet()) {
            var field = contract.fields().get(entry.getKey());
            if (field.referenceResource() == null || entry.getValue() == null) continue;
            var reference = resources.authorizeReference(principal, contract, field);
            try (var scope = reference.openScope()) { data.getById(reference.contract().modelCode(), entry.getValue().toString()); }
        }
        String etagBinding = contract.code() + "@" + principal.tenantId() + ":" + contract.schemaVersion();
        long expected = codec.decodeEtag(ifMatch, etagBinding, pid);
        String requestHash = hash(Map.of("target", pid, "input", parameters, "expectedVersion", expected,
                "contract", protocolHash.fingerprint(action, true)));
        String authorityHash = protocolHash.fingerprint(Map.of("action", action, "grant", authority.policy(), "resource", contract,
                "policy", policy, "binding", resource.sourceBinding(), "tenant", principal.tenantId(), "installation", principal.installationPid()), true);
        String route = "action:" + action.code() + ":execute";
        Set<String> effects = action.effectFields().stream().map(alias -> contract.fields().get(alias).source()).collect(java.util.stream.Collectors.toSet());
        var original = resource.decision();
        var decision = new MachineDataAccessContext.Decision(original.tenantId(), original.installationPid(), original.modelCode(),
                Set.of("update"), original.readableFields(), effects, original.constraints());
        try (var scope = MachineDataAccessContext.authorize(Map.of(contract.modelCode(), decision))) {
            mapper.setStatementTimeout(Math.min(action.timeoutMs(), contract.timeoutMs()) + "ms");
            var before = data.getById(contract.modelCode(), pid);
            OpenApiBusinessAudit.attempt(contract.code(), "execute", before.get("pid").toString());
            if (idempotency.claim(principal.tenantId(), authority.installationId(), route, key, requestHash, Instant.now().plus(Duration.ofHours(24))) == 0) {
                var stored = idempotency.findResponse(principal.tenantId(), authority.installationId(), route, key);
                if (stored == null || !requestHash.equals(stored.requestHash()) || !stored.expiresAt().isAfter(Instant.now())
                        || !"completed".equals(stored.status()) || stored.responseStatus() == null) throw conflict();
                if (!authorityHash.equals(stored.authorityHash())) throw new AccessDeniedException("Business API replay authority changed");
                OpenApiBusinessAudit.result(contract.code(), "execute", pid, true);
                Map<String, Object> replay = new LinkedHashMap<>(decode(stored.responseBody(), OBJECT)); replay.put("idempotentReplay", true);
                return new OpenApiResourceService.Result(stored.responseStatus(), replay, decode(stored.responseHeaders(), HEADERS), true);
            }
            if (version(before) != expected) throw new OpenApiPreconditionException("If-Match version is stale");
            var request = new CommandExecuteRequest(); request.setTargetRecordPid(pid); request.setExpectedVersion(Math.toIntExact(expected));
            request.setPayload(mapped); request.setClientRequestId("public-action:" + hash(Map.of("installation", principal.installationPid(), "route", route, "key", key)));
            request.setAuditContext(Map.of("channel", "open_api", "installationPid", principal.installationPid()));
            try (var cas = MachineDataAccessContext.withExpectedVersion(contract.modelCode(), pid, expected)) {
                MetaContext.runWithExternalCommandPermission(action.permission(), () -> commands.execute(action.sourceCode(), request));
            } catch (com.auraboot.framework.meta.exception.RecordVersionConflictException | com.auraboot.framework.exception.ConflictException changed) {
                throw new OpenApiPreconditionException("If-Match version changed during the command", changed);
            }
            requireDeadline(deadline);
            var record = data.getById(contract.modelCode(), pid);
            String etag = codec.encodeEtag(etagBinding, pid, version(record));
            Map<String, Object> body = Map.of("action", action.code(), "resource", policy.project(contract, record), "etag", etag, "idempotentReplay", false);
            Map<String, String> headers = Map.of("ETag", etag);
            if (idempotency.completeResponse(principal.tenantId(), authority.installationId(), route, key, requestHash, 200,
                    encode(body), encode(headers), authorityHash, pid) != 1) throw new IllegalStateException("Business API response was not persisted");
            OpenApiBusinessAudit.result(contract.code(), "execute", pid, false);
            return new OpenApiResourceService.Result(200, body, headers, false);
        }
    }
    private long version(Map<String, Object> row) {
        if (!(row.get("row_version") instanceof Number number) || number.longValue() < 1) throw new IllegalStateException("Public target row version unavailable");
        return number.longValue();
    }
    private void requireDeadline(long deadline) {
        if (System.nanoTime() > deadline) throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "open_api_action_timeout");
    }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "open_api_action_idempotency_conflict"); }
    private String hash(Object value) {
        return protocolHash.fingerprint(value, false);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Business API response cannot be encoded", invalid); }
    }
    private <T> T decode(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); } catch (JsonProcessingException invalid) { throw new IllegalStateException("Stored business API response is invalid", invalid); }
    }
}
