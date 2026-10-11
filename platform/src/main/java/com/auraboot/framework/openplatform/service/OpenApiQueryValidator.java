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

    public record SourceField(String code, String name, Set<OpenApiResourceContract.Type> types) { }
    public record SourceResource(String code, int schemaVersion) { }
    public record SourceModel(String modelCode, String name, List<SourceResource> resources) { }
    public record SourceView(String queryCode, String name, String fingerprint, List<SourceModel> sources,
                             List<SourceField> fields, Set<String> parameters, String rootPidParameter,
                             int maxRows, int timeoutMs) { }

    /** Uses the execution catalog and validator type rules without exposing SQL or private identifiers. */
    public SourceView describe(Long tenant, String code) {
        var source = queries.describeExecution(code); var query = source.definition();
        require("published".equals(query.getStatus()) && query.getConnectorPid() == null && query.getFromSql() != null
                && "read".equals(query.getActionCode()) && source.sourceModels().contains(query.getResourceCode()),
                "Public queries require a published SQL read NamedQuery");
        var policy = query.getPolicy() == null ? new com.auraboot.framework.meta.entity.NamedQueryPolicy() : query.getPolicy();
        require(!Boolean.TRUE.equals(policy.getSelfAnchoredSources()), "Human self-anchored queries cannot be published to machines");
        validateOrdering(query);
        var published = resources.publishedContracts(tenant);
        var models = source.sourceModels().stream().sorted().map(model -> {
            var metadata = resources.sourceForAdministration(model);
            var bindings = published.stream().filter(resource -> model.equals(resource.modelCode())
                    && resource.operations().contains(OpenApiResourceContract.Operation.LIST))
                    .sorted(Comparator.comparing(OpenApiResourceContract::code))
                    .map(resource -> new SourceResource(resource.code(), resource.schemaVersion())).toList();
            return new SourceModel(model, metadata.name(), bindings);
        }).toList();
        var fields = query.getFields().stream().map(field -> {
            Set<OpenApiResourceContract.Type> types = EnumSet.noneOf(OpenApiResourceContract.Type.class);
            for (var type : OpenApiResourceContract.Type.values()) if (field.getDataType() != null && typeMatches(type, field.getDataType())) types.add(type);
            require(!types.isEmpty(), "Unsupported query output type");
            return new SourceField(field.getFieldCode(), field.getDisplayName(), Set.copyOf(types));
        }).toList();
        var root = policy.getRootAccess();
        if (root != null) require(source.sourceModels().contains(root.getModelCode()) && !Boolean.TRUE.equals(root.getAllowCollaborator())
                && (root.getActionCode() == null || "read".equals(root.getActionCode())), "Machine query root requires its own read source grant");
        return new SourceView(code, query.getTitle(), source.fingerprint(), models, fields, consumedParameters(query),
                root == null ? null : root.getPidParam(), policy.getEffectiveMaxRows(com.auraboot.framework.meta.entity.NamedQueryStatus.PUBLISHED), policy.getEffectiveTimeoutMs());
    }

    public NamedQueryService.ExecutionDefinition validateResource(Long tenant, OpenApiResourceContract resource,
            com.auraboot.framework.meta.dto.ModelDefinition model) {
        var definition = queries.describeExecution(model.getSourceRef());
        var contract = new OpenApiQueryContract(resource.code(), model.getSourceRef(), resource.schemaVersion(),
                definition.fingerprint(), Map.of("execute", resource.scopes().values().iterator().next()),
                resource.sourceResources(), Map.of(), resource.fields().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> resourceProjection(entry.getValue()))), resource.maxPageSize(),
                resource.maxPageSize() + 1, resource.timeoutMs(), 1);
        var source = validate(tenant, contract, false);
        Map<String, com.auraboot.framework.meta.dto.NamedQueryFieldDTO> fields = new HashMap<>();
        source.definition().getFields().forEach(field -> fields.put(field.getFieldCode(), field));
        require(fields.containsKey("pid") && Boolean.TRUE.equals(fields.get("pid").getSortable()),
                "Virtual resources require a sortable PID output");
        resource.fields().values().forEach(field -> {
            var output = fields.get(field.source());
            require(!field.sortable() || Boolean.TRUE.equals(output.getSortable()), "Virtual resource sort exceeds source capability");
            if (output.getOperators() != null && output.getOperators().length > 0) {
                var operators = Arrays.stream(output.getOperators()).map(value -> value.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
                require(operators.containsAll(field.operators()), "Virtual resource filter exceeds source capability");
            }
        });
        return source;
    }

    private OpenApiResourceContract.Field resourceProjection(OpenApiResourceContract.Field field) {
        require(field.readable() && !field.writable(), "Virtual resource outputs are read-only");
        return new OpenApiResourceContract.Field(field.source(), field.type(), true, false, field.required(), field.nullable(),
                Set.of(), false, null, field.properties().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> resourceProjection(entry.getValue()))),
                field.items() == null ? null : resourceProjection(field.items()));
    }

    public NamedQueryService.ExecutionDefinition validate(Long tenant, OpenApiQueryContract contract) {
        return validate(tenant, contract, true);
    }

    private NamedQueryService.ExecutionDefinition validate(Long tenant, OpenApiQueryContract contract, boolean queryRoute) {
        if (queryRoute) fixedRoutes.requireAvailableQueryRoute(contract);
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
        validateOrdering(query);
        contract.fields().values().forEach(field -> {
            String type = types.get(field.source());
            require(type != null && typeMatches(field.type(), type), "Public output type/source does not match the NamedQuery");
        });
        var consumed = consumedParameters(query);
        require(consumed.equals(contract.parameters().keySet()), "Query parameters must exactly match declared source inputs");
        return source;
    }
    private void validateOrdering(com.auraboot.framework.meta.dto.NamedQueryDTO query) {
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
    }
    private Set<String> consumedParameters(com.auraboot.framework.meta.dto.NamedQueryDTO query) {
        Set<String> consumed = new HashSet<>();
        List<String> fragments = new ArrayList<>(List.of(query.getFromSql()));
        if (query.getBaseWhere() != null) fragments.add(query.getBaseWhere().toString());
        query.getFields().forEach(field -> fragments.add(field.getColumnExpr()));
        fragments.forEach(fragment -> { var match = PARAMETER.matcher(fragment); while (match.find()) consumed.add(match.group(1)); });
        require(!consumed.contains("currentUserId") && !consumed.contains("currentUserPid"), "Machine query cannot depend on a human identity parameter");
        consumed.remove("tenantId");
        return Collections.unmodifiableSet(new TreeSet<>(consumed));
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
