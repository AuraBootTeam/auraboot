package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.entity.*;
import com.auraboot.framework.meta.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import java.util.*;

/** Protects resolved model-column projections before serialization to export artifacts. */
@Service
@RequiredArgsConstructor
public class NamedQueryFieldProtection {
    private final DataPermissionEngine policies;
    private final FieldMaskService masks;
    private final MetaModelService models;
    private final NamedQuerySourceModels sources;
    private final DataDomainService domains;
    private final com.auraboot.framework.permission.engine.PermissionEvaluator permissionEvaluator;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    public record Protection(Map<String, String> aliases,
                             List<FieldMaskRule> policies, List<FieldMaskConfig> configs) { }
    public record Plan(JsonNode evidence, List<Protection> protections, Map<String, String> sourceScopes) { }

    public Plan prepare(NamedQuery query, List<NamedQueryField> fields) {
        return prepare(query, fields, "export");
    }

    public Plan prepare(NamedQuery query, List<NamedQueryField> fields, String context) {
        var sourceModels = sources.resolve(MetaContext.getCurrentTenantId(), query.getFromSql(), fields);
        Long memberId = MetaContext.getCurrentMemberId();
        if (memberId == null) memberId = MetaContext.getCurrentUserId();
        for (String model : new TreeSet<>(sourceModels.values())) {
            if (memberId == null || !permissionEvaluator.canAction(memberId, model, "read")) {
                throw new AccessDeniedException("Access denied for named query source: " + model);
            }
        }
        Set<String> resources = new TreeSet<>(sourceModels.values());
        if (query.getResourceCode() != null && !query.getResourceCode().isBlank()) resources.add(query.getResourceCode());
        var evidence = JSON.createObjectNode();
        evidence.set("sourceModels", JSON.valueToTree(sourceModels));
        Map<String, String> sourceScopes = new TreeMap<>();
        Map<String, String> modelScopes = new HashMap<>();
        for (var source : sourceModels.entrySet()) {
            String scope = modelScopes.computeIfAbsent(source.getValue(), model -> {
                String permit = CommandPermitDataAccess.rowFilter(model, MetaContext.getCurrentUserId());
                String row = permit != null ? permit : policies.buildRowFilter(MetaContext.getCurrentTenantId(), model, "read", MetaContext.getCurrentUserId());
                String domain = domains.buildDomainFilter(model, MetaContext.getCurrentUserId());
                return java.util.stream.Stream.of(row, domain).filter(value -> value != null && !value.isBlank())
                        .map(value -> "(" + value.trim().replaceFirst("(?i)^(AND|WHERE)\\s+", "") + ")")
                        .collect(java.util.stream.Collectors.joining(" AND "));
            });
            sourceScopes.put(source.getKey(), scope);
        }
        evidence.set("sourceScopes", JSON.valueToTree(sourceScopes));
        var groups = evidence.putObject("protections");
        List<Protection> protections = new ArrayList<>();
        for (String resource : resources) {
            Plan group = prepareResource(query, fields, resource, context);
            groups.set(resource, group.evidence());
            protections.addAll(group.protections());
        }
        return new Plan(evidence, List.copyOf(protections), Map.copyOf(sourceScopes));
    }

    private Plan prepareResource(NamedQuery query, List<NamedQueryField> fields, String resource, String context) {
        Long user = MetaContext.getCurrentUserId();
        List<FieldMaskRule> rules = policies.getFieldMaskRules(MetaContext.getCurrentTenantId(), resource, user);
        List<FieldMaskConfig> configs = masks.getEffectiveConfigs(resource, user, context);
        if (rules.isEmpty() && configs.isEmpty()) return empty();
        Map<String, String> protectedColumns = new HashMap<>();
        Set<String> protectedFields = new HashSet<>();
        rules.forEach(rule -> protectedFields.add(rule.getFieldCode()));
        configs.forEach(config -> protectedFields.add(config.getFieldCode()));
        for (String field : protectedFields) protectedColumns.put(models.getColumnName(resource, field), field);
        String table = NamedQuerySourceModels.identity(models.getTableName(resource));
        Map<String, String> aliases = new LinkedHashMap<>();
        var origins = new NamedQueryColumnLineage().resolve(query.getFromSql(), fields);
        origins.forEach((alias, origin) -> {
            if (origin.opaqueFunction()) throw new AccessDeniedException("Protected export requires resolved function semantics");
            for (var column : origin.columns()) {
                if (!NamedQuerySourceModels.identity(column.table()).equals(table)) continue;
                String field = protectedColumns.get(column.column());
                if (field != null) {
                    if (!origin.direct() || origin.columns().size() != 1)
                        throw new AccessDeniedException("Protected field expression requires explicit output protection");
                    aliases.put(alias, field);
                }
            }
        });
        var evidence = JSON.createObjectNode();
        evidence.set("policies", JSON.valueToTree(rules));
        var configEvidence = evidence.putArray("configs");
        for (FieldMaskConfig config : configs) {
            com.fasterxml.jackson.databind.node.ObjectNode value = JSON.valueToTree(config);
            value.remove(List.of("createdAt", "updatedAt"));
            configEvidence.add(value);
        }
        evidence.set("aliases", JSON.valueToTree(aliases));
        return new Plan(evidence, List.of(new Protection(Map.copyOf(aliases), List.copyOf(rules), List.copyOf(configs))), Map.of());
    }

    public String rewrite(Plan plan, String sql) {
        return new NamedQuerySourceScopeRewriter().rewrite(sql, plan.sourceScopes());
    }

    public List<Map<String, Object>> apply(Plan plan, List<Map<String, Object>> records) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> record : records) {
            Map<String, Object> output = new LinkedHashMap<>(record);
            for (Protection protection : plan.protections()) protection.aliases().forEach((alias, field) -> {
                if (!output.containsKey(alias)) return;
                Map<String, Object> input = new LinkedHashMap<>();
                input.put(field, output.get(alias));
                Object value = policies.applyFieldMasking(List.of(input), protection.policies()).get(0).get(field);
                for (FieldMaskConfig config : protection.configs()) {
                    if (field.equals(config.getFieldCode()) && value != null) {
                        value = masks.maskValue(value.toString(), config.getMaskType(),
                                config.getMaskPattern(), config.getReplacementChar());
                    }
                }
                output.put(alias, value);
            });
            result.add(output);
        }
        return result;
    }

    private static Plan empty() { return new Plan(JSON.createObjectNode(), List.of(), Map.of()); }
}
