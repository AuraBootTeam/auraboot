package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.formula.FormulaFunctionRegistry;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.VirtualFieldEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;

import com.auraboot.framework.meta.security.SqlSafetyUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Virtual Field Engine implementation.
 * Handles SpEL evaluation, materialization via SQL UPDATE, and dependency graph analysis.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualFieldEngineImpl implements VirtualFieldEngine {

    private final MetaModelService metaModelService;
    private final DynamicDataMapper dynamicDataMapper;
    private final FormulaFunctionRegistry formulaFunctionRegistry;

    private final ExpressionParser spelParser = new SpelExpressionParser();

    @Override
    public Object evaluate(String expression, Map<String, Object> context) {
        if (expression == null || expression.isEmpty()) {
            return null;
        }

        try {
            EvaluationContext evalContext = buildSpelContext(context);
            return spelParser.parseExpression(expression).getValue(evalContext);
        } catch (Exception e) {
            log.warn("Failed to evaluate expression '{}': {}", expression, e.getMessage());
            return null;
        }
    }

    @Override
    public void materialize(String modelCode, String recordId, List<String> changedFields) {
        if (changedFields == null || changedFields.isEmpty()) {
            return;
        }

        log.debug("Materializing fields for model={}, record={}, changed={}", modelCode, recordId, changedFields);

        ModelDefinition modelDef = metaModelService.getModelDefinition(modelCode).orElse(null);
        if (modelDef == null || modelDef.getFields() == null) {
            return;
        }

        // Find MATERIALIZED fields whose dependencies intersect with changedFields
        List<FieldDefinition> fieldsToMaterialize = modelDef.getFields().stream()
                .filter(FieldDefinition::isMaterialized)
                .filter(f -> f.getComputeExpression() != null)
                .filter(f -> f.getComputeDependencies() != null &&
                        !Collections.disjoint(f.getComputeDependencies(), changedFields))
                .collect(Collectors.toList());

        if (fieldsToMaterialize.isEmpty()) {
            return;
        }

        // Get computation order to handle chained dependencies
        List<String> computationOrder = getComputationOrder(modelCode);
        fieldsToMaterialize.sort(Comparator.comparingInt(
                f -> computationOrder.indexOf(f.getCode())));

        // Execute SQL UPDATE for each materialized field
        Long tenantId = MetaContext.getCurrentTenantId();
        for (FieldDefinition field : fieldsToMaterialize) {
            try {
                // Validate identifiers and expression to prevent SQL injection
                SqlSafetyUtils.validateIdentifier(modelDef.getTableName(), "materialize table name");
                SqlSafetyUtils.validateIdentifier(field.getColumnName(), "materialize column name");
                SqlSafetyUtils.validateSqlFragment(field.getComputeExpression());

                String updateSql = String.format(
                        "UPDATE %s SET %s = (%s) WHERE tenant_id = #{params.tenantId} AND id = #{params.recordId}",
                        modelDef.getTableName(),
                        field.getColumnName(),
                        field.getComputeExpression()
                );

                Map<String, Object> params = new HashMap<>();
                params.put("tenantId", tenantId);
                params.put("recordId", recordId);

                dynamicDataMapper.selectByQuery(updateSql, params);
                log.debug("Materialized field {} for record {}", field.getCode(), recordId);
            } catch (Exception e) {
                log.error("Failed to materialize field {} for record {}: {}",
                        field.getCode(), recordId, e.getMessage());
            }
        }
    }

    @Override
    public List<String> validateDependencyGraph(String modelCode) {
        ModelDefinition modelDef = metaModelService.getModelDefinition(modelCode).orElse(null);
        if (modelDef == null || modelDef.getFields() == null) {
            return Collections.emptyList();
        }

        // Build adjacency list for computed fields
        Map<String, List<String>> graph = new HashMap<>();
        Set<String> computedFields = new HashSet<>();

        for (FieldDefinition field : modelDef.getFields()) {
            if (field.isVirtual() && field.getComputeDependencies() != null) {
                computedFields.add(field.getCode());
                graph.put(field.getCode(), new ArrayList<>(field.getComputeDependencies()));
            }
        }

        // Detect cycles using DFS with coloring
        Set<String> white = new HashSet<>(computedFields); // Not visited
        Set<String> gray = new HashSet<>(); // In current path
        List<String> cyclePath = new ArrayList<>();

        for (String node : computedFields) {
            if (white.contains(node)) {
                if (hasCycleDfs(node, graph, white, gray, cyclePath, computedFields)) {
                    return cyclePath;
                }
            }
        }

        return Collections.emptyList();
    }

    @Override
    public List<String> getComputationOrder(String modelCode) {
        ModelDefinition modelDef = metaModelService.getModelDefinition(modelCode).orElse(null);
        if (modelDef == null || modelDef.getFields() == null) {
            return Collections.emptyList();
        }

        // Build in-degree map and adjacency list
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>(); // field -> list of fields that depend on it
        Set<String> computedFields = new HashSet<>();

        for (FieldDefinition field : modelDef.getFields()) {
            if (field.isVirtual() && field.getComputeDependencies() != null) {
                String code = field.getCode();
                computedFields.add(code);
                inDegree.put(code, 0);
            }
        }

        // Calculate in-degrees (only count dependencies on other computed fields)
        for (FieldDefinition field : modelDef.getFields()) {
            if (field.isVirtual() && field.getComputeDependencies() != null) {
                String code = field.getCode();
                int degree = 0;
                for (String dep : field.getComputeDependencies()) {
                    if (computedFields.contains(dep)) {
                        degree++;
                        dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(code);
                    }
                }
                inDegree.put(code, degree);
            }
        }

        // Kahn's algorithm for topological sort
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            result.add(current);

            List<String> deps = dependents.get(current);
            if (deps != null) {
                for (String dep : deps) {
                    int newDegree = inDegree.get(dep) - 1;
                    inDegree.put(dep, newDegree);
                    if (newDegree == 0) {
                        queue.add(dep);
                    }
                }
            }
        }

        return result;
    }

    // ==================== Private Helpers ====================

    private EvaluationContext buildSpelContext(Map<String, Object> context) {
        SimpleEvaluationContext evalContext = SimpleEvaluationContext.forReadOnlyDataBinding().build();

        // Register formula functions to enable #FUNCTION() syntax
        formulaFunctionRegistry.registerToContext(evalContext);

        if (context != null) {
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                evalContext.setVariable(entry.getKey(), entry.getValue());
            }
        }
        return evalContext;
    }

    private boolean hasCycleDfs(String node, Map<String, List<String>> graph,
                                 Set<String> white, Set<String> gray,
                                 List<String> cyclePath, Set<String> computedFields) {
        white.remove(node);
        gray.add(node);
        cyclePath.add(node);

        List<String> dependencies = graph.get(node);
        if (dependencies != null) {
            for (String dep : dependencies) {
                if (!computedFields.contains(dep)) {
                    continue; // Only check cycles among computed fields
                }
                if (gray.contains(dep)) {
                    cyclePath.add(dep);
                    return true; // Cycle found
                }
                if (white.contains(dep) && hasCycleDfs(dep, graph, white, gray, cyclePath, computedFields)) {
                    return true;
                }
            }
        }

        gray.remove(node);
        cyclePath.remove(cyclePath.size() - 1);
        return false;
    }
}
