package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.exception.RecordVersionConflictException;
import com.auraboot.framework.meta.exception.MetaRecordNotFoundException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.openplatform.mapper.OpenApiIdempotencyMapper;
import com.auraboot.framework.openplatform.mapper.OpenApiPublicationMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import static com.auraboot.framework.openplatform.service.OpenApiResourceContract.Operation;

/** Stable public resource protocol over the production model/data services. */
@Service
@RequiredArgsConstructor
public class OpenApiResourceService {
    private final OpenApiResourceAuthorizer authorizer;
    private final DynamicDataService data;
    private final OpenApiVirtualResourceReader virtualReader;
    private final OpenApiProtocolTokenCodec codec;
    private final OpenApiIdempotencyMapper idempotency;
    private final OpenApiPublicationMapper publications;
    private final OpenPlatformSecretCodec secrets;
    private final ObjectMapper json;
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() { };

    public record Result(int status, Map<String, Object> body, Map<String, String> headers, boolean replay) {
        public ResponseEntity<Map<String, Object>> response() {
            var builder = ResponseEntity.status(status);
            headers.forEach(builder::header);
            if (replay) builder.header("Idempotent-Replay", "true");
            return builder.body(body);
        }
    }

    @Transactional(readOnly = true)
    public Result get(MachinePrincipal principal, String code, String pid) {
        requirePid(pid);
        var authorization = authorizer.authorize(principal, code, Operation.GET);
        try (var scope = authorization.openScope()) {
            timeout(authorization.contract());
            var row = authorization.sourceBinding().queryFingerprint() == null
                    ? data.getById(authorization.contract().modelCode(), pid) : virtualReader.get(authorization, pid);
            return result(200, authorization, row, pid);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(MachinePrincipal principal, String code, int limit, String cursor,
                                     String filters, String sort, String direction) {
        var authorization = authorizer.authorize(principal, code, Operation.LIST);
        var contract = authorization.contract();
        if (limit < 1 || limit > contract.maxPageSize()) throw invalid("Page size exceeds the published budget");
        String alias = sort == null ? "pid" : sort;
        String order = direction == null ? "ASC" : direction;
        if (!Set.of("ASC", "DESC").contains(order)) throw invalid("Published sort direction must be ASC or DESC");
        var field = contract.fields().get(alias);
        if (field == null || !authorization.policy().readableFields().contains(alias)
                || (!"pid".equals(alias) && !field.sortable())) throw invalid("Sort field is not published for this installation");
        var conditions = filters(contract, authorization.policy(), filters);
        String binding = code + "@" + hash(Map.of("tenant", principal.tenantId(), "installation", principal.installationPid(),
                "authority", authorityHash(authorization), "filters", conditions, "sort", alias, "direction", order, "limit", limit), false);
        var request = DynamicQueryRequest.builder().pageNum(1).pageSize(limit + 1).conditions(conditions).build();
        boolean defaultOrder = "pid".equals(alias) && "ASC".equals(order);
        if (defaultOrder) {
            request.setCursor(cursor == null ? "" : codec.decodeCursor(boundedCursor(cursor), binding, contract.schemaVersion()));
        } else {
            request.setSortFields("pid".equals(alias)
                    ? List.of(SortField.builder().fieldName("pid").direction(SortField.SortDirection.valueOf(order)).build())
                    : List.of(SortField.builder().fieldName(field.source()).direction(SortField.SortDirection.valueOf(order)).build(),
                        SortField.builder().fieldName("pid").direction(SortField.SortDirection.ASC).build()));
            if (cursor != null) {
                JsonNode continuation = parse(codec.decodeCursor(boundedCursor(cursor), binding, contract.schemaVersion()));
                if (!continuation.isObject() || continuation.size() != 2 || !continuation.has("value") || !continuation.has("pid"))
                    throw invalid("Invalid composite cursor");
                String lastPid = continuation.path("pid").asText(); requirePid(lastPid);
                Object value = json.convertValue(continuation.get("value"), Object.class);
                value = value == null ? null : contract.validateFilterValue(alias, value);
                request.setKeysetContinuation(new DynamicQueryRequest.KeysetContinuation(field.source(), order,
                        jdbcValue(field.type(), value), lastPid));
            }
        }
        try (var scope = authorization.openScope()) {
            timeout(contract);
            var page = authorization.sourceBinding().queryFingerprint() == null
                    ? data.list(contract.modelCode(), request) : virtualReader.list(authorization, request);
            var selected = page.getRecords().stream().limit(limit).toList();
            boolean hasMore = page.getRecords().size() > limit;
            String next = null;
            if (hasMore) {
                var last = selected.getLast();
                String pid = Objects.toString(last.get("pid"), null); requirePid(pid);
                String continuation;
                if (defaultOrder) continuation = pid;
                else {
                    Map<String, Object> value = new LinkedHashMap<>(); value.put("pid", pid);
                    value.put("value", fieldValue(field, last.get(field.source())));
                    continuation = encode(value);
                }
                next = codec.encodeCursor(binding, contract.schemaVersion(), continuation);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("items", selected.stream().map(row -> project(authorization, row)).toList());
            response.put("hasMore", hasMore); response.put("nextCursor", next);
            return response;
        }
    }

    /** Claim, model mutation and original response commit or roll back together. */
    @Transactional
    public Result write(MachinePrincipal principal, String code, Operation operation, String pid,
                         String key, String ifMatch, Map<String, Object> input) {
        if (!Set.of(Operation.CREATE, Operation.UPDATE, Operation.DELETE).contains(operation)) throw invalid("Write operation required");
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,160}")) throw invalid("Invalid Idempotency-Key");
        if (operation != Operation.CREATE) requirePid(pid);
        var authorization = authorizer.authorize(principal, code, operation);
        var contract = authorization.contract();
        Map<String, Object> mapped = operation == Operation.DELETE ? Map.of()
                : authorization.policy().mapWrite(contract, input, operation == Operation.CREATE);
        String etagBinding = etagBinding(principal, contract);
        Long expected = operation == Operation.CREATE ? null : codec.decodeEtag(ifMatch, etagBinding, pid);
        var intent = new LinkedHashMap<String, Object>();
        intent.put("operation", operation.code()); intent.put("pid", pid); intent.put("input", input);
        intent.put("expectedVersion", expected); intent.put("schemaVersion", contract.schemaVersion());
        String requestHash = hash(intent, false);
        String authorityHash = authorityHash(authorization);
        String route = "resource:" + code + ":" + operation.code();
        try (var scope = authorization.openScope()) {
            timeout(contract);
            validateReferences(principal, contract, mapped);
            if (idempotency.claim(principal.tenantId(), authorization.installationId(), route, key, requestHash,
                    Instant.now().plus(Duration.ofHours(24))) == 0) {
                var previous = idempotency.findResponse(principal.tenantId(), authorization.installationId(), route, key);
                if (previous == null || !requestHash.equals(previous.requestHash())) throw conflict("Idempotency key belongs to another intent");
                if (!previous.expiresAt().isAfter(Instant.now())) throw conflict("Idempotency key expired; use a new key");
                if (!authorityHash.equals(previous.authorityHash())) throw new AccessDeniedException("Replay authorization changed");
                if (!"completed".equals(previous.status()) || previous.responseStatus() == null) throw conflict("Idempotency request is not complete");
                if (operation != Operation.DELETE) {
                    // A stored response cannot recover visibility after the target has left the installation row scope.
                    try { data.getById(contract.modelCode(), previous.responseRecordPid()); }
                    catch (MetaRecordNotFoundException absent) { throw new AccessDeniedException("Replay target is no longer accessible", absent); }
                }
                com.auraboot.framework.openplatform.security.OpenApiBusinessAudit.result(code, operation.code(), previous.responseRecordPid(), true);
                return new Result(previous.responseStatus(), decode(previous.responseBody(), OBJECT),
                        decode(previous.responseHeaders(), HEADERS), true);
            }
            String resultPid = pid;
            Result result;
            if (operation == Operation.CREATE) {
                var created = data.create(contract.modelCode(), new LinkedHashMap<>(mapped));
                resultPid = Objects.toString(created.get("pid"), null); requirePid(resultPid);
                result = result(201, authorization, created, resultPid);
            } else {
                var existing = data.getById(contract.modelCode(), pid);
                if (rowVersion(existing) != expected) throw new OpenApiPreconditionException("If-Match version is stale");
                try (var cas = MachineDataAccessContext.withExpectedVersion(contract.modelCode(), pid, expected)) {
                    if (operation == Operation.UPDATE) result = result(200, authorization,
                            data.update(contract.modelCode(), pid, new LinkedHashMap<>(mapped)), pid);
                    else { data.delete(contract.modelCode(), pid); result = new Result(204, null, Map.of(), false); }
                } catch (RecordVersionConflictException | com.auraboot.framework.exception.ConflictException conflict) {
                    throw new OpenApiPreconditionException("If-Match version changed during the write", conflict);
                }
            }
            if (idempotency.completeResponse(principal.tenantId(), authorization.installationId(), route, key, requestHash,
                    result.status(), encode(result.body()), encode(result.headers()), authorityHash, resultPid) != 1)
                throw new IllegalStateException("Original resource write response could not be persisted");
            com.auraboot.framework.openplatform.security.OpenApiBusinessAudit.result(code, operation.code(), resultPid, false);
            return result;
        }
    }

    private void validateReferences(MachinePrincipal principal, OpenApiResourceContract contract, Map<String, Object> mapped) {
        for (var field : contract.fields().values()) {
            Object pid = mapped.get(field.source());
            if (field.referenceResource() == null || pid == null) continue;
            var target = authorizer.authorizeReference(principal, contract, field);
            try (var targetScope = target.openScope()) {
                timeout(target.contract());
                try { data.getById(target.contract().modelCode(), pid.toString()); }
                catch (MetaRecordNotFoundException absent) {
                    throw new AccessDeniedException("Reference target is unavailable to this installation", absent);
                }
            }
        }
        // Restore the owning operation's tighter SQL deadline after checking target resources.
        timeout(contract);
    }

    private Result result(int status, OpenApiResourceAuthorizer.Authorization authorization, Map<String, Object> row, String pid) {
        String etag = authorization.sourceBinding().queryFingerprint() == null
                ? codec.encodeEtag(etagBinding(MachineDataAccessContext.principal(), authorization.contract()), pid, rowVersion(row))
                : "\"" + hash(Map.of("authority", authorityHash(authorization), "record", project(authorization, row)), false) + "\"";
        Map<String, String> headers = status == 201
                ? Map.of("ETag", etag, "Location", "/api/open/v1/resources/" + authorization.contract().code() + "/" + pid)
                : Map.of("ETag", etag);
        return new Result(status, project(authorization, row), headers, false);
    }

    private Map<String, Object> project(OpenApiResourceAuthorizer.Authorization authorization, Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>(row);
        authorization.contract().fields().values().forEach(field -> normalized.computeIfPresent(field.source(),
                (key, value) -> fieldValue(field, value)));
        return authorization.policy().project(authorization.contract(), normalized);
    }

    private Object fieldValue(OpenApiResourceContract.Field field, Object value) {
        if (value == null) return null;
        if ((field.type() == OpenApiResourceContract.Type.OBJECT || field.type() == OpenApiResourceContract.Type.ARRAY) && value instanceof String text)
            return json.convertValue(parse(text), Object.class);
        if (field.type() == OpenApiResourceContract.Type.DATE || field.type() == OpenApiResourceContract.Type.DATETIME) {
            if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
            if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().toString();
        }
        return value;
    }

    private List<QueryCondition> filters(OpenApiResourceContract contract, OpenApiInstallationPolicy policy, String text) {
        if (text == null) return List.of();
        if (text.length() > 32768) throw invalid("Filter payload exceeds the published budget");
        JsonNode values = parse(text);
        if (!values.isArray() || values.size() > contract.maxConditions()) throw invalid("Condition budget exceeded");
        List<QueryCondition> conditions = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isObject() || value.size() < 2 || value.size() > 3
                    || !value.path("field").isTextual() || !value.path("operator").isTextual()
                    || !Set.of("field", "operator", "value").containsAll(fieldNames(value))) throw invalid("Invalid public filter");
            String alias = value.path("field").asText(), operator = value.path("operator").asText();
            var field = contract.fields().get(alias);
            if (field == null || !policy.readableFields().contains(alias) || !field.operators().contains(operator)) throw invalid("Filter is not published for this installation");
            Object argument = null;
            if ("IS_NULL".equals(operator)) { if (value.has("value")) throw invalid("IS_NULL has no value"); }
            else {
                if (!value.hasNonNull("value")) throw invalid("Non-null filter value required");
                if ("IN".equals(operator)) {
                    JsonNode items = value.get("value");
                    if (!items.isArray() || items.isEmpty() || items.size() > 100) throw invalid("Bounded IN list required");
                    List<Object> arguments = new ArrayList<>();
                    for (JsonNode item : items) arguments.add(jdbcValue(field.type(), contract.validateFilterValue(alias, json.convertValue(item, Object.class))));
                    argument = arguments;
                } else argument = jdbcValue(field.type(), contract.validateFilterValue(alias, json.convertValue(value.get("value"), Object.class)));
            }
            conditions.add(QueryCondition.builder().fieldName(field.source()).operator(QueryCondition.Operator.valueOf(operator)).value(argument).build());
        }
        return List.copyOf(conditions);
    }

    private Set<String> fieldNames(JsonNode value) { Set<String> names = new HashSet<>(); value.fieldNames().forEachRemaining(names::add); return names; }
    private Object jdbcValue(OpenApiResourceContract.Type type, Object value) {
        if (value == null) return null;
        return switch (type) {
            case DATE -> java.sql.Date.valueOf(value.toString());
            case DATETIME -> java.sql.Timestamp.from(Instant.parse(value.toString()));
            case DECIMAL -> new java.math.BigDecimal(value.toString());
            default -> value;
        };
    }
    private String authorityHash(OpenApiResourceAuthorizer.Authorization authorization) {
        return hash(Map.of("contract", authorization.contract(), "policy", authorization.policy(), "installation", authorization.installationId(), "sources", authorization.sourceAuthorities()), true);
    }
    private String etagBinding(MachinePrincipal principal, OpenApiResourceContract contract) {
        return contract.code() + "@" + principal.tenantId() + ":" + contract.schemaVersion();
    }
    private long rowVersion(Map<String, Object> row) {
        Object version = row.get("row_version");
        if (!(version instanceof Number number) || number.longValue() < 1) throw new IllegalStateException("Public resource row version unavailable");
        return number.longValue();
    }
    private String boundedCursor(String cursor) { if (cursor.length() > 8192 || cursor.isBlank()) throw invalid("Invalid cursor budget"); return cursor; }
    private void timeout(OpenApiResourceContract contract) { publications.setStatementTimeout(contract.timeoutMs() + "ms"); }
    private void requirePid(String pid) { if (pid == null || !pid.matches("[A-Za-z0-9_-]{1,64}") || pid.matches("[0-9]+")) throw invalid("Public PID required"); }
    private String hash(Object value, boolean sortArrays) { return secrets.sha256(encode(canonical(json.valueToTree(value), sortArrays))); }
    private JsonNode canonical(JsonNode value, boolean sortArrays) {
        if (value.isObject()) {
            var object = json.createObjectNode(); new TreeSet<>(fieldNames(value)).forEach(key -> object.set(key, canonical(value.get(key), sortArrays))); return object;
        }
        if (value.isArray()) {
            List<JsonNode> items = new ArrayList<>(); value.forEach(item -> items.add(canonical(item, sortArrays)));
            if (sortArrays) items.sort(Comparator.comparing(JsonNode::toString));
            var array = json.createArrayNode(); items.forEach(array::add); return array;
        }
        return value;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("Invalid public protocol JSON", error); }
    }
    private JsonNode parse(String value) {
        try {
            JsonNode parsed = json.readTree(value);
            if (parsed == null) throw invalid("Non-empty public protocol JSON required");
            return parsed;
        }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("Invalid public protocol JSON", error); }
    }
    private <T> T decode(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Stored public response is invalid", error); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
