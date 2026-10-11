package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.exception.*;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.*;

/** Read-only resource adapter through the canonical, source-scoped NamedQuery executor. */
@Service
@RequiredArgsConstructor
public class OpenApiVirtualResourceReader {
    private final NamedQueryService queries;
    private final ObjectMapper json;

    public Map<String, Object> get(OpenApiResourceAuthorizer.Authorization authority, String pid) {
        var records = execute(authority, DynamicQueryRequest.builder().pageNum(1).pageSize(2).build(), pid).getRecords();
        if (records.isEmpty()) throw new MetaRecordNotFoundException(authority.contract().modelCode(), pid);
        if (records.size() != 1) throw new IllegalStateException("Virtual resource PID is ambiguous");
        return records.getFirst();
    }

    public PaginationResult<Map<String, Object>> list(OpenApiResourceAuthorizer.Authorization authority, DynamicQueryRequest request) {
        return execute(authority, request, null);
    }

    private PaginationResult<Map<String, Object>> execute(OpenApiResourceAuthorizer.Authorization authority,
                                                         DynamicQueryRequest request, String pid) {
        NamedQueryTestRequest internal = new NamedQueryTestRequest();
        internal.setPage(1); internal.setSize(request.getPageSize());
        internal.setExpectedDefinitionFingerprint(authority.sourceBinding().queryFingerprint());
        internal.setExecutionDeadlineNanos(System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(authority.contract().timeoutMs()));
        internal.setPublishedOutputFields(authority.policy().readableFields().stream()
                .map(alias -> authority.contract().fields().get(alias).source()).collect(java.util.stream.Collectors.toSet()));
        var boundaries = new ArrayList<NamedQueryTestRequest.ResourceCondition>();
        authority.decision().constraints().forEach(constraint -> boundaries.add(
                new NamedQueryTestRequest.ResourceCondition(constraint.field(), "in", constraint.values())));
        if (pid != null) boundaries.add(new NamedQueryTestRequest.ResourceCondition("pid", "eq", pid));
        internal.setResourceConditions(boundaries);
        if (request.getConditions() != null) {
            internal.setResourceFilters(request.getConditions().stream().map(condition ->
                    new NamedQueryTestRequest.ResourceCondition(condition.getFieldName(),
                            condition.getOperator().name().toLowerCase(Locale.ROOT),
                            condition.getValue())).toList());
        }
        var orders = json.createArrayNode();
        if (request.getSortFields() == null || request.getSortFields().isEmpty()) {
            orders.addObject().put("field", "pid").put("direction", "asc");
        } else request.getSortFields().forEach(sort -> orders.addObject().put("field", sort.getFieldName()).put("direction", sort.getDirection().name()));
        internal.setOrderConditions(orders); internal.setResourceCursor(request.getCursor());
        internal.setResourceContinuation(request.getKeysetContinuation());
        try {
            return queries.executeQuery(authority.sourceBinding().sourceRef(), internal);
        } catch (NamedQuerySourceChangedException changed) {
            // CATCH: execution-time pin failure uses the same public conflict contract as preflight.
            throw new OpenApiPublicationService.SourceBindingConflictException(changed);
        } catch (NamedQueryRateLimitException exhausted) {
            // CATCH: preserve the canonical machine query quota error and denial audit.
            throw new OpenApiQueryService.QueryRateLimitException(exhausted);
        } catch (org.springframework.dao.QueryTimeoutException timeout) {
            // CATCH: translate the canonical SQL timeout without exposing source details.
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
                    "open_api_query_timeout", timeout);
        } catch (MetaServiceException unsafe) {
            // CATCH: invalid or inaccessible source execution cannot fall back to a broader data path.
            throw new AccessDeniedException("Published virtual resource cannot be safely evaluated", unsafe);
        }
    }
}
