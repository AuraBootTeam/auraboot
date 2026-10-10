package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.meta.service.NamedQueryService;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** Validates against the canonical query catalog, including every resolved physical source. */
@Component
public class OpenApiQueryValidator {
    private final NamedQueryService queries;
    private final OpenApiPublicationService resources;
    private final OpenApiCapabilityRegistry fixedRoutes;
    private static final Pattern PARAMETER = Pattern.compile("#\\{params\\.([A-Za-z][A-Za-z0-9_]*)}");

    public OpenApiQueryValidator(NamedQueryService queries, OpenApiPublicationService resources) { this(queries, resources, List.of()); }
    @org.springframework.beans.factory.annotation.Autowired
    public OpenApiQueryValidator(NamedQueryService queries, OpenApiPublicationService resources, List<OpenApiCapabilityContributor> contributors) {
        this.queries = queries;this.resources = resources;this.fixedRoutes = new OpenApiCapabilityRegistry(contributors);
    }

    public NamedQueryService.ExecutionDefinition validate(Long tenant, OpenApiQueryContract contract) {
        fixedRoutes.requireAvailableQueryRoute(contract);
        var source = queries.describeExecution(contract.queryCode()); var query = source.definition();
        require("published".equals(query.getStatus()) && query.getConnectorPid() == null && query.getFromSql() != null,
                "Public queries require a published SQL NamedQuery");
        require(contract.sourceFingerprint().equals(source.fingerprint()), "Published query source fingerprint changed");
        require(contract.sourceResources().keySet().equals(source.sourceModels()), "Every query source must have exactly one published resource binding");
        require(source.sourceModels().contains(query.getResourceCode()) && "read".equals(query.getActionCode()),
                "Public query must declare a read action on a granted source");
        contract.sourceResources().forEach((model, code) -> {
            var resource = resources.active(tenant, code).orElseThrow(() -> new IllegalArgumentException("Query source resource is not published"));
            require(model.equals(resource.modelCode()) && resource.operations().contains(OpenApiResourceContract.Operation.LIST),
                    "Query source binding requires LIST on its declared model");
        });
        var policy = query.getPolicy();
        if (policy != null) {
            require(!Boolean.TRUE.equals(policy.getSelfAnchoredSources()), "Human self-anchored queries cannot be published to machines");
            require(contract.maxRows() <= policy.getEffectiveMaxRows(com.auraboot.framework.meta.entity.NamedQueryStatus.PUBLISHED)
                    && contract.timeoutMs() <= policy.getEffectiveTimeoutMs(), "Public query exceeds source execution budget");
            if (policy.getRootAccess() != null) {
                var root = policy.getRootAccess();
                require(contract.sourceResources().containsKey(root.getModelCode()) && !Boolean.TRUE.equals(root.getAllowCollaborator())
                        && (root.getActionCode() == null || "read".equals(root.getActionCode())), "Machine query root requires its own read source grant");
                var parameter = contract.parameters().get(root.getPidParam());
                require(parameter != null && parameter.type() == OpenApiResourceContract.Type.PID && parameter.required() && !parameter.nullable(),
                        "Root query requires a declared non-null PID parameter");
            }
        }
        Map<String, String> types = new HashMap<>();
        query.getFields().forEach(field -> types.put(field.getFieldCode(), field.getDataType()));
        var ordering = query.getDefaultOrder();
        require(ordering != null && (ordering.isObject() || (ordering.isArray() && !ordering.isEmpty() && ordering.size() <= 16)),
                "Public query requires a bounded declared result ordering");
        List<com.fasterxml.jackson.databind.JsonNode> orders = new ArrayList<>();
        if (ordering.isArray()) ordering.forEach(orders::add); else orders.add(ordering);
        Set<String> ordered = new HashSet<>();
        orders.forEach(order -> {
            String alias = order.path("field").asText();String direction = order.path("direction").asText("asc").toLowerCase(Locale.ROOT);
            require(order.isObject() && ordered.add(alias) && Set.of("asc", "desc").contains(direction)
                    && query.getFields().stream().anyMatch(field -> alias.equals(field.getFieldCode()) && Boolean.TRUE.equals(field.getSortable())),
                    "Query ordering must use declared sortable source outputs");
        });
        contract.fields().values().forEach(field -> {
            String type = types.get(field.source());
            require(type != null && typeMatches(field.type(), type), "Public output type/source does not match the NamedQuery");
        });
        Set<String> consumed = new HashSet<>();
        List<String> fragments = new ArrayList<>(List.of(query.getFromSql()));
        if (query.getBaseWhere() != null) fragments.add(query.getBaseWhere().toString());
        query.getFields().forEach(field -> fragments.add(field.getColumnExpr()));
        fragments.forEach(fragment -> { var match = PARAMETER.matcher(fragment); while (match.find()) consumed.add(match.group(1)); });
        require(!consumed.contains("currentUserId") && !consumed.contains("currentUserPid"), "Machine query cannot depend on a human identity parameter");
        consumed.remove("tenantId");
        require(consumed.equals(contract.parameters().keySet()), "Query parameters must exactly match declared source inputs");
        return source;
    }
    private boolean typeMatches(OpenApiResourceContract.Type type, String dataType) {
        String value = dataType.toLowerCase(Locale.ROOT);
        return switch (type) {
            case STRING -> Set.of("string", "text", "enum").contains(value);
            case PID -> Set.of("string", "reference", "pid").contains(value);
            case INTEGER -> Set.of("integer", "long", "bigint", "int", "number").contains(value);
            case DECIMAL -> Set.of("decimal", "numeric", "number").contains(value);
            case BOOLEAN -> Set.of("boolean", "bool").contains(value);
            case DATE -> "date".equals(value);
            case DATETIME -> Set.of("datetime", "timestamp").contains(value);
            case OBJECT -> "json".equals(value);
            case ARRAY -> Set.of("json", "array").contains(value);
        };
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
