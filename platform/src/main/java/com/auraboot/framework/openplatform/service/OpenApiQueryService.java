package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.meta.dto.NamedQueryTestRequest;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.openplatform.mapper.OpenApiPublicationMapper;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Public typed query execution through the production NamedQuery authorization and SQL engine. */
@Service
@RequiredArgsConstructor
public class OpenApiQueryService {
    private final OpenApiQueryPublicationService publications;
    private final OpenApiQueryValidator validator;
    private final OpenApiResourceAuthorizer resources;
    private final NamedQueryService queries;
    private final OpenApiPublicationMapper mapper;
    private final OpenApiProtocolTokenCodec codec;
    private final OpenPlatformSecretCodec secrets;
    private final ObjectMapper json;

    public record ExecuteRequest(Map<String, Object> parameters, Integer limit, String cursor) { }
    @Transactional(readOnly = true)
    public Map<String, Object> execute(MachinePrincipal principal, String code, ExecuteRequest request) {
        if (principal == null || principal.tenantId() == null || principal.installationPid() == null)
            throw new AccessDeniedException("Installed machine principal required");
        var authority = publications.authority(principal, code).orElseThrow(() -> {
            if (publications.active(principal.tenantId(), code).isEmpty()) return new ResponseStatusException(HttpStatus.NOT_FOUND, "open_api_query_not_published");
            return new AccessDeniedException("Current query token, scope and installation grant required");
        });
        var contract = authority.contract(); var policy = authority.policy(); policy.validate(contract);
        if (!principal.scopes().contains(contract.scopes().get("execute"))) throw new AccessDeniedException("Published query execute scope required");
        if (request == null) throw new IllegalArgumentException("Public query request required");
        var parameters = contract.mapParameters(request.parameters() == null ? Map.of() : request.parameters());
        int limit = request.limit() == null ? Math.min(20, contract.maxPageSize()) : request.limit();
        if (limit < 1 || limit > contract.maxPageSize()) throw new IllegalArgumentException("Query page budget exceeded");
        try { validator.validate(principal.tenantId(), contract); }
        catch (IllegalArgumentException | com.auraboot.framework.meta.exception.MetaServiceException changed) {
            throw new AccessDeniedException("Published query source is unavailable", changed);
        }
        Map<String, MachineDataAccessContext.Decision> decisions = new HashMap<>();
        Map<String, Object> sourceAuthorities = new TreeMap<>();
        contract.sourceResources().forEach((model, resource) -> {
            var source = resources.authorize(principal, resource, OpenApiResourceContract.Operation.LIST);
            if (!model.equals(source.contract().modelCode())) throw new AccessDeniedException("Published query source model changed");
            decisions.put(model, source.decision()); sourceAuthorities.put(model, Map.of("contract", source.contract(), "policy", source.policy()));
        });
        String binding = "query:" + code + "@" + hash(Map.of("tenant", principal.tenantId(), "installation", principal.installationPid(),
                "contract", contract, "policy", policy, "sources", sourceAuthorities), true) + ":" + hash(Map.of("parameters", parameters, "limit", limit), false);
        int page = 1;
        if (request.cursor() != null) {
            if (request.cursor().isBlank() || request.cursor().length() > 8192) throw new IllegalArgumentException("Invalid query cursor");
            String value = codec.decodeCursor(request.cursor(), binding, contract.schemaVersion());
            if (!value.matches("[1-9][0-9]{0,8}")) throw new IllegalArgumentException("Invalid query cursor position");
            page = Integer.parseInt(value);
        }
        long offset = (long) (page - 1) * limit;
        if (offset >= contract.maxRows()) throw new IllegalArgumentException("Query result budget exceeded");
        boolean acquired = false;
        for (int slot = 0; slot < contract.maxConcurrent(); slot++) {
            if (mapper.tryQuerySlot(principal.tenantId(), code, slot)) { acquired = true; break; }
        }
        if (!acquired) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "open_api_query_concurrency_exceeded");
        try (var scope = MachineDataAccessContext.authorize(decisions)) {
            mapper.setStatementTimeout(contract.timeoutMs() + "ms");
            NamedQueryTestRequest internal = new NamedQueryTestRequest(); internal.setParameters(jdbcParameters(contract, parameters));
            internal.setPage(page); internal.setSize(limit); internal.setExpectedDefinitionFingerprint(contract.sourceFingerprint());
            internal.setExecutionDeadlineNanos(System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(contract.timeoutMs()));
            internal.setPublishedOutputFields(policy.readableFields().stream().map(alias -> contract.fields().get(alias).source())
                    .collect(java.util.stream.Collectors.toSet()));
            var result = queries.executeQuery(contract.queryCode(), internal);
            int available = (int) Math.min(limit, contract.maxRows() - offset);
            var records = result.getRecords().stream().limit(available).map(row -> project(contract, policy, row)).toList();
            long total = Math.min(result.getTotal(), contract.maxRows());
            boolean hasMore = offset + records.size() < total;
            String cursor = hasMore ? codec.encodeCursor(binding, contract.schemaVersion(), Integer.toString(page + 1)) : null;
            Map<String, Object> response = new LinkedHashMap<>(); response.put("items", records);response.put("total", total);
            response.put("hasMore", hasMore);response.put("nextCursor", cursor);response.put("schemaVersion", contract.schemaVersion());
            return response;
        } catch (org.springframework.dao.QueryTimeoutException timeout) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "open_api_query_timeout", timeout);
        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "open_api_query_unavailable", unavailable);
        } catch (com.auraboot.framework.meta.exception.MetaServiceException unsafe) {
            throw new AccessDeniedException("Published query cannot be safely evaluated", unsafe);
        }
    }
    private Map<String, Object> jdbcParameters(OpenApiQueryContract contract, Map<String, Object> input) {
        Map<String, Object> result = new HashMap<>();
        input.forEach((key, value) -> {
            var type = contract.parameters().get(key).type();
            if (value == null) result.put(key, null);
            else if (type == OpenApiResourceContract.Type.DATE) result.put(key, java.sql.Date.valueOf(value.toString()));
            else if (type == OpenApiResourceContract.Type.DATETIME) result.put(key, java.sql.Timestamp.from(java.time.Instant.parse(value.toString())));
            else if (type == OpenApiResourceContract.Type.DECIMAL) result.put(key, new java.math.BigDecimal(value.toString()));
            else if (type == OpenApiResourceContract.Type.ARRAY) {
                // Declared SQL binds public arrays as JSON text and must cast them explicitly.
                try { result.put(key, json.writeValueAsString(value)); }
                catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException("Query array cannot be serialized", invalid); }
            }
            else result.put(key, value);
        });
        return result;
    }
    private Map<String, Object> project(OpenApiQueryContract contract, OpenApiQueryPolicy policy, Map<String, Object> row) {
        Map<String, Object> values = new LinkedHashMap<>(row);
        contract.fields().values().forEach(field -> values.computeIfPresent(field.source(), (key, value) -> {
            if (field.type() != OpenApiResourceContract.Type.OBJECT && field.type() != OpenApiResourceContract.Type.ARRAY) return value;
            if (!(value instanceof String) && !"org.postgresql.util.PGobject".equals(value.getClass().getName())) return value;
            try { return json.readValue(value.toString(), Object.class); }
            catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Published query JSON result is invalid", invalid); }
        }));
        return contract.project(values, policy.readableFields());
    }
    private String hash(Object value, boolean sortArrays) {
        return secrets.sha256(normalize(json.valueToTree(value), sortArrays).toString());
    }
    private JsonNode normalize(JsonNode node, boolean sortArrays) {
        if (node.isObject()) {
            var result = json.createObjectNode();var keys = new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, normalize(node.get(key), sortArrays)));return result;
        }
        if (node.isArray()) {
            List<JsonNode> values = new ArrayList<>();node.forEach(value -> values.add(normalize(value, sortArrays)));
            if (sortArrays) values.sort(Comparator.comparing(JsonNode::toString));
            var result = json.createArrayNode(); values.forEach(result::add);return result;
        }
        return node;
    }
}
