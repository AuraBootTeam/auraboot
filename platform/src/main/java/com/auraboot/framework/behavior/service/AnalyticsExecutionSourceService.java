package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.service.ReportAggregateQueryService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Objects;

/** Revalidates a human adoption without accepting client-supplied goals or run identities. */
@Service
@RequiredArgsConstructor
public class AnalyticsExecutionSourceService {
    private final DynamicDataService data;
    private final ReportAggregateQueryService queries;
    private final UserPermissionService permissions;
    private final ObjectMapper json;
    private static final String PREFIX = "core_dashboard_";
    public record Source(String goal, Map<String, Object> binding) {}

    public Source resolve(String adoptionPid) {
        Long user = MetaContext.getCurrentUserId();
        Long tenant = MetaContext.getCurrentTenantId();
        if (user == null || tenant == null || !permissions.hasPermission(user, "analytics.suggestion.execute")) {
            throw new AccessDeniedException("Analytics execution permission required");
        }
        com.auraboot.framework.agent.identity.ExecutionPrincipalContext.current().ifPresent(principal -> {
            if (principal.type() == com.auraboot.framework.agent.identity.ExecutionPrincipal.Type.SANDBOX
                    || principal.tenantId() != tenant || principal.actorUserId() != user) {
                throw new AccessDeniedException("Execution principal cannot use a formal analytics adoption");
            }
        });
        if (adoptionPid == null || adoptionPid.length() != 26) throw invalid("Invalid adoption reference");
        Map<String, Object> adoption = owned(AnalyticsSuggestionCommandHandler.ADOPTION, adoptionPid, user);
        String versionPid = String.valueOf(adoption.get(PREFIX + "version_pid"));
        Map<String, Object> version = owned(AnalyticsSuggestionCommandHandler.VERSION, versionPid, user);
        try {
            var query = json.readTree(String.valueOf(version.get(PREFIX + "query")));
            String hash = AnalyticsQueryFingerprint.of(query);
            if (!Objects.equals(hash, version.get(PREFIX + "query_hash"))
                    || !Objects.equals(adoption.get(PREFIX + "analysis_id"), version.get(PREFIX + "analysis_id"))) {
                throw invalid("Adoption source does not match its immutable version");
            }
            queries.execute(json.treeToValue(query, AggregateQueryRequest.class));
            Object intentValue = version.get(PREFIX + "execution_intent");
            if (intentValue == null) throw invalid("This suggestion has no execution goal");
            var intent = AnalyticsExecutionIntent.parse(json.readValue(String.valueOf(intentValue), Map.class));
            return new Source(intent.goal(), Map.of(
                    "adoptionPid", adoptionPid, "versionPid", versionPid,
                    "analysisId", version.get(PREFIX + "analysis_id"), "queryHash", hash,
                    "decisionMode", adoption.get(PREFIX + "decision_mode"), "actorUserId", user,
                    "intentHash", AnalyticsQueryFingerprint.of(json.valueToTree(intent.toMap()))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw invalid("Stored analytics execution source is invalid");
        }
    }

    private Map<String, Object> owned(String model, String pid, Long user) {
        Map<String, Object> row = data.getById(model, pid);
        if (row == null || !Objects.equals(String.valueOf(user), String.valueOf(row.get("created_by")))) {
            throw invalid("Analytics execution source is unavailable to this user");
        }
        return row;
    }
    private static BusinessException invalid(String message) {
        return new BusinessException(ResponseCode.BadParam, message);
    }
}
