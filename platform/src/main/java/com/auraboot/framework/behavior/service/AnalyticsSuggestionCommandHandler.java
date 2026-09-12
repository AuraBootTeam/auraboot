package com.auraboot.framework.behavior.service;

import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.agent.service.StepContext;
import com.auraboot.framework.conversation.TurnScopeContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.behavior.outcome.BehaviorOutcomeEvent;
import com.auraboot.framework.behavior.outcome.BehaviorOutcomePublisher;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.bi.service.ReportAggregateQueryService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable suggestion versions and explicit user decisions, owned by published commands. */
@Component
@RequiredArgsConstructor
public class AnalyticsSuggestionCommandHandler implements CommandHandlerExtension {
    public static final String VERSION = "core_dashboard_suggestion";
    public static final String ADOPTION = "core_dashboard_adoption";
    public static final String PROPOSE = "core_dashboard:propose_suggestion";
    public static final String ADOPT = "core_dashboard:adopt_suggestion";
    private static final String PREFIX = "core_dashboard_";
    private final DynamicDataService data;
    private final DynamicDataMapper locks;
    private final BehaviorEventMapper events;
    private final ReportAggregateQueryService queries;
    private final BehaviorOutcomePublisher outcomes;
    private final ObjectMapper json;

    @Override public String getCommandType() { return PROPOSE; }
    @Override public boolean supports(String code) { return PROPOSE.equals(code) || ADOPT.equals(code); }
    @Override public Set<String> getSupportedCommandTypes() { return Set.of(PROPOSE, ADOPT); }
    @Override public boolean requiresDslPersistence(String code, Map<String, Object> config, CommandExecuteRequest request) { return false; }

    @Override @Transactional
    public Object execute(CommandContext context) {
        String command = context.commandType();
        if (!supports(command) || !command.equals(MetaContext.getAuthorizedCommandCode())
                || !MetaContext.hasCommandPermitScope() || MetaContext.getCurrentUserId() == null || MetaContext.getCurrentTenantId() == null
                || !Objects.equals(context.tenantId(), MetaContext.getCurrentTenantId())) {
            throw invalid("A tenant-bound authorized suggestion command is required");
        }
        if (context.dryRun()) throw invalid("Suggestion commands require explicit committed execution");
        Map<String, Object> payload = Objects.requireNonNull(context.payload(), "Suggestion payload is required");
        // Keep successor records out of the framework's flat handler-result write-back path.
        return Map.of("record", PROPOSE.equals(command) ? propose(payload) : adopt(payload));
    }

    private Map<String, Object> propose(Map<String, Object> payload) {
        allowed(payload, Set.of("analysisId", "query", "title", "content", "previousPid", "requestId"));
        String analysis = text(payload, "analysisId", 40);
        String title = text(payload, "title", 200);
        String content = text(payload, "content", 4000);
        JsonNode query = json.valueToTree(payload.get("query"));
        String hash = verifySource(analysis, query);
        String requestKey = requestKey("proposal", payload);
        String proposalHash = AnalyticsQueryFingerprint.of(json.valueToTree(new TreeMap<>(payload)));
        lock(requestKey);
        Map<String, Object> priorRequest = find(VERSION, "request_key", requestKey);
        if (priorRequest != null) {
            if (!proposalHash.equals(priorRequest.get(PREFIX + "proposal_hash"))) throw invalid("Request identity was already used for another proposal");
            return priorRequest;
        }
        String group = UUID.randomUUID().toString();
        int version = 1;
        String previousPid = payload.get("previousPid") == null ? null : text(payload, "previousPid", 26);
        if (previousPid != null) {
            Map<String, Object> previous = ownedVersion(previousPid);
            if (!analysis.equals(previous.get(PREFIX + "analysis_id")) || !hash.equals(previous.get(PREFIX + "query_hash"))) {
                throw invalid("A revision must retain its original analysis and query");
            }
            group = String.valueOf(previous.get(PREFIX + "group_key"));
            lock("version:" + group);
            var latest = list(VERSION, "group_key", group).getRecords();
            if (latest.isEmpty() || !previousPid.equals(String.valueOf(latest.get(0).get("pid")))) {
                throw invalid("The suggestion has a newer version; reopen it before revising");
            }
            version = ((Number) previous.get(PREFIX + "version")).intValue() + 1;
        }
        String origin = ExecutionPrincipalContext.current().map(principal -> {
            if (principal.type() == com.auraboot.framework.agent.identity.ExecutionPrincipal.Type.SANDBOX
                    || principal.tenantId() != MetaContext.getCurrentTenantId()
                    || principal.actorUserId() != MetaContext.getCurrentUserId()) {
                throw invalid("This execution principal cannot produce a formal suggestion");
            }
            return "agent_generated";
        }).orElse(isAgentExecution() ? "agent_generated" : "human_authored");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put(PREFIX + "title", title); record.put(PREFIX + "content", content);
        record.put(PREFIX + "analysis_id", analysis); record.put(PREFIX + "query_hash", hash);
        record.put(PREFIX + "group_key", group); record.put(PREFIX + "version", version);
        record.put(PREFIX + "query", json.convertValue(query, Map.class));
        record.put(PREFIX + "origin", origin); record.put(PREFIX + "request_key", requestKey);
        record.put(PREFIX + "proposal_hash", proposalHash);
        if (previousPid != null) record.put(PREFIX + "previous_pid", previousPid);
        Map<String, Object> saved = data.create(VERSION, record);
        publish("analytics_suggestion_proposed", saved, analysis, Map.of("queryHash", hash, "suggestionKey", group,
                "suggestionVersion", version, "proposalOrigin", origin));
        return saved;
    }

    private Map<String, Object> adopt(Map<String, Object> payload) {
        allowed(payload, Set.of("versionPid", "requestId"));
        if (isAgentExecution()) throw invalid("Agent execution cannot supply explicit user adoption");
        String versionPid = text(payload, "versionPid", 26);
        Map<String, Object> version = ownedVersion(versionPid);
        String analysis = String.valueOf(version.get(PREFIX + "analysis_id"));
        String hash = verifySource(analysis, storedQuery(version));
        if (!hash.equals(version.get(PREFIX + "query_hash"))) throw invalid("Suggestion query provenance changed");
        String requestKey = requestKey("adoption", payload);
        lock(requestKey);
        Map<String, Object> priorRequest = find(ADOPTION, "request_key", requestKey);
        if (priorRequest != null) {
            if (!versionPid.equals(priorRequest.get(PREFIX + "version_pid"))) throw invalid("Request identity was already used for another version");
            return priorRequest;
        }
        String adoptionKey = identity("adopted-version:" + versionPid);
        lock(adoptionKey);
        if (find(ADOPTION, "adoption_key", adoptionKey) != null) throw invalid("This version was already adopted; reuse the original request identity");
        String origin = String.valueOf(version.get(PREFIX + "origin"));
        if (!Set.of("human_authored", "agent_generated").contains(origin)) throw invalid("Suggestion producer is unavailable");
        Map<String, Object> record = new LinkedHashMap<>();
        for (String field : List.of("title", "analysis_id", "query_hash", "group_key", "version")) record.put(PREFIX + field, version.get(PREFIX + field));
        record.put(PREFIX + "version_pid", versionPid);
        record.put(PREFIX + "decision_mode", "agent_generated".equals(origin) ? "ai_assisted" : "human");
        record.put(PREFIX + "request_key", requestKey); record.put(PREFIX + "adoption_key", adoptionKey);
        Map<String, Object> saved = data.create(ADOPTION, record);
        publish("analytics_suggestion_adopted", saved, analysis, Map.of("queryHash", hash,
                "suggestionVersionPid", versionPid, "decisionMode", record.get(PREFIX + "decision_mode")));
        return saved;
    }

    private Map<String, Object> ownedVersion(String pid) {
        Map<String, Object> record = data.getById(VERSION, pid);
        if (record == null || !Objects.equals(String.valueOf(record.get("created_by")), String.valueOf(MetaContext.getCurrentUserId()))) {
            throw invalid("Suggestion version is unavailable to this user");
        }
        return record;
    }

    private String verifySource(String analysis, JsonNode query) {
        String hash = events.findSuccessfulQueryHash(MetaContext.getCurrentTenantId(), MetaContext.getCurrentUserId(), analysis);
        if (hash == null || !query.isObject() || !hash.equals(AnalyticsQueryFingerprint.of(query))) throw invalid("Analysis source is unavailable or its query differs");
        // The ordinary query service enforces current semantic/model permissions on every call.
        queries.execute(json.convertValue(query, AggregateQueryRequest.class));
        return hash;
    }

    private JsonNode storedQuery(Map<String, Object> version) {
        // DynamicDataService's documented read shape exposes JSON/JSONB fields as JSON strings.
        if (!(version.get(PREFIX + "query") instanceof String value)) throw invalid("Stored suggestion query is unavailable");
        try { return json.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw invalid("Stored suggestion query is invalid"); }
    }

    private PaginationResult<Map<String, Object>> list(String model, String field, String value) {
        return data.list(model, DynamicQueryRequest.builder().pageNum(1).pageSize(1)
                .conditions(List.of(QueryCondition.builder().fieldName(PREFIX + field).operator(QueryCondition.Operator.EQ).value(value).build()))
                .sortFields(List.of(SortField.builder().fieldName(PREFIX + "version").direction(SortField.SortDirection.DESC).build())).build());
    }
    private Map<String, Object> find(String model, String field, String value) {
        var records = list(model, field, value).getRecords();
        return records.isEmpty() ? null : records.get(0);
    }
    private void lock(String key) {
        locks.selectByQuery("SELECT pg_advisory_xact_lock(hashtextextended(#{params.key}, 0))", Map.of("key", "analytics-suggestion:" + MetaContext.getCurrentTenantId() + ":" + key));
    }
    private String requestKey(String action, Map<String, Object> payload) {
        String request = text(payload, "requestId", 36);
        try { if (!UUID.fromString(request).toString().equals(request)) throw invalid("requestId must be a canonical UUID"); }
        catch (IllegalArgumentException error) { throw invalid("requestId must be a canonical UUID"); }
        return identity(action + ":" + request);
    }
    private static boolean isAgentExecution() {
        return ExecutionPrincipalContext.current().isPresent() || TurnScopeContext.get() != null || StepContext.getRunPid() != null;
    }
    private String identity(String suffix) {
        return UUID.nameUUIDFromBytes((MetaContext.getCurrentTenantId() + ":" + MetaContext.getCurrentUserId() + ":" + suffix).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private void publish(String name, Map<String, Object> saved, String analysis, Map<String, Object> props) {
        String pid = String.valueOf(saved.get("pid"));
        boolean inserted = outcomes.publish(BehaviorOutcomeEvent.builder().tenantId(MetaContext.getCurrentTenantId())
                .userId(MetaContext.getCurrentUserId()).eventId(identity(name + ":" + pid)).eventName(name)
                .interactionId(analysis).targetType(name.endsWith("proposed") ? "analytics_suggestion" : "analytics_adoption")
                .targetKey(pid).causedByEventId("analytics_suggestion_adopted".equals(name)
                        ? identity("analytics_suggestion_proposed:" + props.get("suggestionVersionPid")) : null)
                .props(props).build());
        if (!inserted) throw new IllegalStateException("Suggestion outcome identity already exists");
    }
    private static String text(Map<String, Object> payload, String key, int limit) {
        if (!(payload.get(key) instanceof String value) || value.isBlank() || value.length() > limit) throw invalid("Invalid suggestion field: " + key);
        return value;
    }
    private static void allowed(Map<String, Object> payload, Set<String> keys) {
        if (!keys.containsAll(payload.keySet())) throw invalid("Unknown suggestion parameters");
    }
    private static BusinessException invalid(String message) { return new BusinessException(ResponseCode.BadParam, message); }
}
