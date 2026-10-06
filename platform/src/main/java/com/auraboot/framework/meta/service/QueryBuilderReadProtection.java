package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.QueryBuilderDTO;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.impl.NamedQueryFieldProtection;
import com.auraboot.framework.permission.service.FieldPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.*;

/** Adapts structured queries to the existing source-scope and field-protection contract. */
@Service
@RequiredArgsConstructor
public class QueryBuilderReadProtection {
    private final FieldPermissionService fields;
    private final NamedQueryFieldProtection protection;
    private final DynamicDataMapper mapper;

    public record Plan(Map<String, String> visibleColumns, NamedQueryFieldProtection.Plan source) { }

    public Map<String, String> visibleColumns(String modelCode, Map<String, String> columns) {
        if (!MetaContext.exists()) throw new AccessDeniedException("Authenticated query context is required");
        Long memberId = MetaContext.getCurrentMemberId();
        if (memberId == null) memberId = MetaContext.getCurrentUserId();
        if (memberId == null) throw new AccessDeniedException("Query member context is required");
        Set<String> hidden = fields.getFieldPermissions(memberId, modelCode).hiddenFields();
        Map<String, String> visible = new LinkedHashMap<>(columns);
        Set<String> hiddenColumns = new HashSet<>();
        hidden.forEach(field -> { if (columns.containsKey(field)) hiddenColumns.add(columns.get(field)); });
        visible.entrySet().removeIf(entry -> hidden.contains(entry.getKey()) || hiddenColumns.contains(entry.getValue()));
        return Collections.unmodifiableMap(visible);
    }

    public Plan prepare(QueryBuilderDTO dto, Map<String, String> columns, String table) {
        Map<String, String> visible = visibleColumns(dto.getModelCode(), columns);
        Set<String> used = new HashSet<>();
        if (dto.getFields() != null) used.addAll(dto.getFields());
        if (dto.getFilters() != null) dto.getFilters().forEach(filter -> used.add(filter.getFieldName()));
        if (dto.getGroupBy() != null) used.addAll(dto.getGroupBy());
        if (dto.getSortField() != null && !dto.getSortField().isBlank()) used.add(dto.getSortField());
        if (dto.getAggregations() != null) dto.getAggregations().forEach(metric -> used.add(metric.getFieldCode()));
        for (String field : used) {
            if (columns.containsKey(field) && !visible.containsKey(field))
                throw new AccessDeniedException("Hidden query fields cannot be used");
        }
        if (visible.isEmpty()) throw new AccessDeniedException("No visible query fields");
        requireIdentifier(table);
        List<NamedQueryField> projections = new ArrayList<>();
        for (String column : new TreeSet<>(visible.values())) {
            requireIdentifier(column);
            NamedQueryField projection = new NamedQueryField();
            projection.setFieldCode(column);
            projection.setColumnExpr(column);
            projections.add(projection);
        }
        NamedQuery query = new NamedQuery();
        query.setFromSql("SELECT " + String.join(", ", new TreeSet<>(visible.values())) + " FROM " + table);
        NamedQueryFieldProtection.Plan source = protection.prepare(query, projections, "list");
        Set<String> masked = new HashSet<>();
        source.protections().forEach(group -> masked.addAll(group.aliases().keySet()));
        Set<String> predicates = new HashSet<>();
        if (dto.getFilters() != null) dto.getFilters().forEach(filter -> predicates.add(filter.getFieldName()));
        if (dto.getGroupBy() != null) predicates.addAll(dto.getGroupBy());
        if (dto.getSortField() != null) predicates.add(dto.getSortField());
        if (dto.getAggregations() != null) dto.getAggregations().forEach(metric -> predicates.add(metric.getFieldCode()));
        for (String field : predicates) {
            if (masked.contains(visible.get(field)))
                throw new AccessDeniedException("Protected fields cannot drive query predicates or aggregates");
        }
        return new Plan(visible, source);
    }

    /** Comparisons infer values, so every consumed field must allow unmasked inference. */
    public Plan prepareComparison(String modelCode, Collection<String> inputs,
                                  Map<String, String> columns, String table) {
        if (inputs == null || inputs.isEmpty() || !columns.keySet().containsAll(inputs)) {
            throw new AccessDeniedException("Comparison fields must be registered");
        }
        QueryBuilderDTO dto = new QueryBuilderDTO();
        dto.setModelCode(modelCode);
        dto.setFields(List.copyOf(inputs));
        // Grouping and matching share the same prohibition on protected value inference.
        dto.setGroupBy(List.copyOf(inputs));
        return prepare(dto, columns, table);
    }

    public List<Map<String, Object>> execute(Plan plan, String sql, Map<String, Object> parameters) {
        String scoped = protection.rewrite(plan.source(), sql);
        List<Map<String, Object>> rows = mapper.selectByQueryWithoutTenant(scoped, parameters);
        return protection.apply(plan.source(), rows == null ? List.of() : rows);
    }

    /** Execute a scalar count after field-inference planning and source-scope rewriting. */
    public long executeCount(Plan plan, String sql, Map<String, Object> parameters) {
        String scoped = protection.rewrite(plan.source(), sql);
        Long count = mapper.countByQueryWithoutTenant(scoped, parameters);
        if (count == null || count < 0) throw new IllegalStateException("Protected count did not return a valid value");
        return count;
    }

    private static void requireIdentifier(String value) {
        if (value == null || !value.matches("[a-zA-Z_][a-zA-Z0-9_]*"))
            throw new AccessDeniedException("Invalid protected query identifier");
    }
}
