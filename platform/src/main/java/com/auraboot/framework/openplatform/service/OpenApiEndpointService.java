package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.openplatform.mapper.OpenApiPublicationMapper;
import com.auraboot.framework.openplatform.security.OpenApiBusinessAudit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Performs no network forwarding or Controller reflection; only registered, authorized business boundaries execute. */
@Service
@RequiredArgsConstructor
public class OpenApiEndpointService {
    public static final class SourceChangedException extends ResponseStatusException {
        public SourceChangedException(IllegalArgumentException cause) {
            super(HttpStatus.CONFLICT, "open_api_endpoint_source_changed", cause);
        }
    }
    private final OpenApiEndpointPublicationService publications;
    private final OpenApiEndpointValidator validator;
    private final OpenApiResourceService resources;
    private final OpenApiActionService actions;
    private final OpenApiQueryService queries;
    private final OpenApiPublicationMapper mapper;
    private final OpenApiProtocolHash hashes;
    private final ObjectMapper json;
    private final OpenApiSynchronousIntegrationService integrations;

    @Transactional
    public OpenApiResourceService.Result invoke(MachinePrincipal principal, String code, String method, String recordPid,
            Map<String, List<String>> query, Map<String, Object> body, String key, String ifMatch) {
        if (principal == null || principal.tenantId() == null || principal.installationPid() == null)
            throw new AccessDeniedException("Installed machine principal required");
        var authority = publications.authority(principal, code).orElseThrow(() -> {
            if (publications.active(principal.tenantId(), code).isEmpty())
                return new ResponseStatusException(HttpStatus.NOT_FOUND, "open_api_endpoint_not_published");
            return new AccessDeniedException("Current endpoint token, scope and installation grant required");
        });
        var contract = authority.contract(); var kind = contract.kind();
        if (!authority.policy().invoke() || !principal.scopes().contains(contract.scopes().get("invoke")))
            throw new AccessDeniedException("Current endpoint invocation grant required");
        if (!kind.method().equals(method) || kind.target() != (recordPid != null))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "open_api_endpoint_not_published");
        if (recordPid != null && !recordPid.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Public record PID required");
        // CATCH: explicit pre-execution source validation; no business transaction has been invoked yet.
        try { validator.validate(principal.tenantId(), contract); }
        catch (IllegalArgumentException changed) { throw new SourceChangedException(changed); }
        if (query == null || query.size() > 6 || query.values().stream().anyMatch(values -> values == null || values.size() != 1))
            throw new IllegalArgumentException("Endpoint query parameters must be singular and bounded");
        if (kind != OpenApiEndpointKind.RESOURCE_LIST && !query.isEmpty()) throw new IllegalArgumentException("Query parameters are not declared by this endpoint");
        if (Set.of(OpenApiEndpointKind.RESOURCE_LIST, OpenApiEndpointKind.RESOURCE_GET, OpenApiEndpointKind.RESOURCE_DELETE).contains(kind)
                && body != null) throw new IllegalArgumentException("This endpoint does not accept a request body");
        if ((kind == OpenApiEndpointKind.RESOURCE_LIST || kind == OpenApiEndpointKind.RESOURCE_GET || kind == OpenApiEndpointKind.QUERY)
                && (key != null || ifMatch != null)) throw new IllegalArgumentException("Read-only endpoints do not accept write preconditions");
        boolean acquired = false;
        for (int slot = 0; slot < contract.maxConcurrent(); slot++) {
            if (mapper.tryQuerySlot(principal.tenantId(), contract.storageCode(), slot)) { acquired = true; break; }
        }
        if (!acquired) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "open_api_endpoint_concurrency_exceeded");
        mapper.setStatementTimeout(contract.timeoutMs() + "ms");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(contract.timeoutMs());
        OpenApiBusinessAudit.attempt(contract.code(), "invoke", recordPid);
        var result = execute(principal, contract, recordPid, query, body, key, ifMatch);
        if (System.nanoTime() > deadline) throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "open_api_endpoint_timeout");
        OpenApiBusinessAudit.result(contract.code(), "invoke", recordPid, result.replay());
        return result;
    }

    private OpenApiResourceService.Result execute(MachinePrincipal principal, OpenApiEndpointContract contract, String pid,
            Map<String, List<String>> query, Map<String, Object> body, String key, String ifMatch) {
        String binding = contract.bindingCode();
        return switch (contract.kind()) {
            case INTEGRATION -> {
                if (ifMatch != null || body == null || !body.keySet().equals(Set.of("input"))
                        || !(body.get("input") instanceof Map<?,?>))
                    throw new IllegalArgumentException("Published integration input object required");
                Map<String,Object> input = json.convertValue(body.get("input"), new TypeReference<>() { });
                yield integrations.execute(principal, binding, contract.code(), key, input);
            }
            case RESOURCE_GET -> resources.get(principal, binding, pid);
            case RESOURCE_LIST -> {
                if (!Set.of("limit", "cursor", "filters", "sort", "direction").containsAll(query.keySet()))
                    throw new IllegalArgumentException("Undeclared list parameter");
                String limit = parameter(query, "limit");
                if (limit != null && !limit.matches("[1-9][0-9]{0,3}")) throw new IllegalArgumentException("Bounded integer limit required");
                yield new OpenApiResourceService.Result(200, resources.list(principal, binding, limit == null ? validator.defaultPageSize(principal.tenantId(), contract) : Integer.parseInt(limit),
                        parameter(query, "cursor"), parameter(query, "filters"), parameter(query, "sort"), parameter(query, "direction")), Map.of(), false);
            }
            case RESOURCE_CREATE, RESOURCE_UPDATE, RESOURCE_DELETE -> {
                if (contract.kind() != OpenApiEndpointKind.RESOURCE_DELETE && body == null) throw new IllegalArgumentException("JSON request object required");
                yield resources.write(principal, binding, OpenApiResourceContract.Operation.valueOf(contract.kind().operation().toUpperCase(Locale.ROOT)),
                        pid, namespacedKey(contract, key), ifMatch, body);
            }
            case ACTION -> {
                if (body == null || !body.keySet().stream().allMatch(Set.of("input", "targetPid")::contains)
                        || !(body.get("input") instanceof Map<?, ?>) || body.containsKey("targetPid") && !(body.get("targetPid") instanceof String))
                    throw new IllegalArgumentException("Published action input and optional target PID required");
                Map<String, Object> input = json.convertValue(body.get("input"), new TypeReference<>() { });
                yield actions.execute(principal, binding, key == null ? null : namespacedKey(contract, key), ifMatch, (String) body.get("targetPid"), input);
            }
            case QUERY -> {
                if (body == null || !Set.of("parameters", "limit", "cursor").containsAll(body.keySet())) throw new IllegalArgumentException("Published query request required");
                var tree = json.valueToTree(body);
                if (tree.has("parameters") && !tree.get("parameters").isObject() || tree.has("limit") && (!tree.get("limit").isIntegralNumber() || !tree.get("limit").canConvertToInt())
                        || tree.has("cursor") && !tree.get("cursor").isTextual()) throw new IllegalArgumentException("Invalid typed query parameters");
                Map<String, Object> parameters = tree.has("parameters") ? json.convertValue(tree.get("parameters"), new TypeReference<>() { }) : Map.of();
                yield new OpenApiResourceService.Result(200, queries.execute(principal, binding, new OpenApiQueryService.ExecuteRequest(parameters,
                        tree.has("limit") ? tree.get("limit").intValue() : null, tree.has("cursor") ? tree.get("cursor").asText() : null)), Map.of(), false);
            }
        };
    }
    private String namespacedKey(OpenApiEndpointContract contract, String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) throw new IllegalArgumentException("Idempotency-Key must contain 8 to 128 supported characters");
        return hashes.fingerprint(Map.of("endpoint", contract.code(), "source", contract.sourceFingerprint(), "key", key), false);
    }
    private String parameter(Map<String, List<String>> query, String key) {
        var values = query.get(key); return values == null ? null : values.getFirst();
    }
}
