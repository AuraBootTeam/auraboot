package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reusable service for computing Roll-Up Summary field values.
 * Extracts the aggregation logic from CommandSideEffectExecutor into a shared service
 * that can be called from multiple places:
 * <ol>
 *   <li>Auto-trigger in command pipeline (SIDE_EFFECT stage)</li>
 *   <li>Batch recalculate API (for data migration / manual refresh)</li>
 * </ol>
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RollUpSummaryService {

    private final DynamicDataMapper dynamicDataMapper;
    private final MetaModelService metaModelService;

    /**
     * Recalculate a single roll-up field for a specific parent record.
     *
     * @param parentModelCode parent model code (e.g. "sales_order")
     * @param parentFieldCode field code on parent to update (e.g. "or_total_amount")
     * @param parentRecordId  the parent record's ID (pid or id value)
     * @param childModelCode  child model code (e.g. "order_line")
     * @param childFieldCode  field code in child to aggregate (e.g. "ol_amount")
     * @param childFkCode     FK field code in child pointing to parent (e.g. "ol_order_id")
     * @param function        aggregate function: SUM, COUNT, AVG, MIN, MAX
     * @param childFilter     optional SQL WHERE fragment
     * @param tenantId        tenant ID
     */
    public void recalculate(String parentModelCode, String parentFieldCode, String parentRecordId,
                            String childModelCode, String childFieldCode, String childFkCode,
                            String function, String childFilter, Long tenantId) {

        String childTable = metaModelService.getTableName(childModelCode);
        String parentTable = metaModelService.getTableName(parentModelCode);

        // Resolve column names from field codes
        String childColumn = metaModelService.getColumnName(childModelCode, childFieldCode);
        if (childColumn == null) childColumn = childFieldCode;
        String childFkColumn = metaModelService.getColumnName(childModelCode, childFkCode);
        if (childFkColumn == null) childFkColumn = childFkCode;
        String parentColumn = metaModelService.getColumnName(parentModelCode, parentFieldCode);
        if (parentColumn == null) parentColumn = parentFieldCode;

        // Validate SQL identifiers
        CommandExecutorUtils.validateSqlIdentifier(childColumn, "rollUp childField");
        CommandExecutorUtils.validateSqlIdentifier(childFkColumn, "rollUp childFk");
        CommandExecutorUtils.validateSqlIdentifier(parentColumn, "rollUp parentField");

        // For COUNT, we don't need a specific child field
        String selectExpr = "count".equals(function) && childFieldCode == null
                ? "1"
                : childColumn;

        String sql = "SELECT " + selectExpr + " FROM " + childTable
                + " WHERE " + childFkColumn + " = #{params.parentId} AND tenant_id = #{params.tenantId}";

        if (childFilter != null && !childFilter.isBlank()) {
            CommandExecutorUtils.validateSqlFragment(childFilter, "rollUp childFilter");
            sql += " AND " + childFilter;
        }

        List<Map<String, Object>> children = dynamicDataMapper.selectByQuery(
                sql, Map.of("parentId", parentRecordId, "tenantId", tenantId));

        // Collect numeric values
        List<BigDecimal> values = new ArrayList<>();
        if (children != null) {
            for (Map<String, Object> child : children) {
                if (child == null) continue;
                Object val = child.get(selectExpr.equals("1") ? "1" : childColumn);
                if (val == null && child.size() == 1) {
                    // Single-column result, get first value regardless of key
                    val = child.values().iterator().next();
                }
                if (val instanceof BigDecimal bd) {
                    values.add(bd);
                } else if (val instanceof Number n) {
                    values.add(BigDecimal.valueOf(n.doubleValue()));
                } else if (val instanceof String s) {
                    try {
                        values.add(new BigDecimal(s));
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                }
            }
        }

        BigDecimal result = CommandSideEffectExecutor.computeAggregate(function, values);

        // Update parent record
        var idEntry = CommandExecutorUtils.resolveRecordIdColumn(parentRecordId);
        dynamicDataMapper.update(parentTable, Map.of(parentColumn, result),
                Map.of("tenant_id", tenantId, idEntry.getKey(), idEntry.getValue()));

        log.info("RollUp {}({}.{}) where {}={} = {} -> {}.{}",
                function, childModelCode, childFieldCode, childFkCode, parentRecordId,
                result, parentModelCode, parentFieldCode);
    }

    /**
     * Batch recalculate a roll-up field for ALL parent records.
     * Used for data migration or fixing inconsistencies.
     *
     * @param parentModelCode parent model code
     * @param parentFieldCode field code on parent
     * @param childModelCode  child model code
     * @param childFieldCode  field code in child to aggregate
     * @param childFkCode     FK field code in child
     * @param function        aggregate function
     * @param childFilter     optional SQL filter
     * @param tenantId        tenant ID
     * @return number of parent records updated
     */
    public int batchRecalculate(String parentModelCode, String parentFieldCode,
                                String childModelCode, String childFieldCode, String childFkCode,
                                String function, String childFilter, Long tenantId) {
        String parentTable = metaModelService.getTableName(parentModelCode);

        // Query all parent record IDs
        String sql = "SELECT pid FROM " + parentTable + " WHERE tenant_id = #{params.tenantId}";
        List<Map<String, Object>> parents = dynamicDataMapper.selectByQuery(
                sql, Map.of("tenantId", tenantId));

        if (parents == null || parents.isEmpty()) {
            log.info("RollUp batch: no parent records found for model '{}'", parentModelCode);
            return 0;
        }

        int count = 0;
        for (Map<String, Object> parent : parents) {
            Object pidObj = parent.get("pid");
            if (pidObj == null) continue;
            String parentRecordId = pidObj.toString();
            try {
                recalculate(parentModelCode, parentFieldCode, parentRecordId,
                        childModelCode, childFieldCode, childFkCode,
                        function, childFilter, tenantId);
                count++;
            } catch (Exception e) {
                log.warn("RollUp batch: failed to recalculate for parent record '{}': {}",
                        parentRecordId, e.getMessage());
            }
        }

        log.info("RollUp batch complete: updated {}/{} parent records for {}.{}",
                count, parents.size(), parentModelCode, parentFieldCode);
        return count;
    }
}
