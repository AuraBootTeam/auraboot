package com.auraboot.framework.meta.service.executor;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.dto.SortField;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Executor for {@code sourceType=sqlView} virtual models.
 *
 * <p>{@code source_ref} holds a PostgreSQL view name. This executor runs a
 * bounded {@code SELECT} against the view with the following safety layers:
 * <ul>
 *     <li>View name and column identifiers validated against a strict regex
 *         ({@link #SAFE_IDENTIFIER}) — never interpolated raw into SQL.</li>
 *     <li>Sort whitelist enforced from
 *         {@link ModelCapabilities#getSortableFields()} — sort on a
 *         non-whitelisted field raises {@link MetaServiceException}.</li>
 *     <li>Filter whitelist enforced from
 *         {@link ModelCapabilities#getFilterableFields()} — same fail-fast
 *         semantics as sort.</li>
 *     <li>Tenant isolation applied when the view exposes a {@code tenant_id}
 *         column (detected via
 *         {@code information_schema.columns}), using the current
 *         {@link MetaContext#getCurrentTenantId()}.</li>
 *     <li>All condition values bound as prepared-statement parameters.</li>
 * </ul>
 *
 * <p>Phase 1 does <strong>not</strong> integrate with the row-level data
 * permission engine used by the physical path — SQL views are expected to
 * be pre-filtered at definition time (or to not require row-level security
 * beyond tenant isolation). Per-document data permission for views is a
 * phase 2 item.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlViewModelExecutor implements ModelDataExecutor {

    /** PostgreSQL identifier: letter/underscore prefix + alphanumerics, max 63 chars. */
    private static final Pattern SAFE_IDENTIFIER =
        Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{0,62}");

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 500;

    private final MetaModelService metaModelService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public String sourceType() {
        return "sqlView";
    }

    @Override
    public PaginationResult<Map<String, Object>> list(String modelCode, DynamicQueryRequest request) {
        ModelDefinition def = requireDefinition(modelCode);
        String viewName = validateIdentifier(def.getSourceRef(), "view name");

        int pageNum = (request.getPageNum() != null && request.getPageNum() > 0) ? request.getPageNum() : 1;
        int pageSize = resolvePageSize(request.getPageSize());
        int offset = (pageNum - 1) * pageSize;

        ModelCapabilities caps = def.getCapabilities() != null
            ? def.getCapabilities() : ModelCapabilities.empty();

        boolean viewHasTenantId = viewHasTenantIdColumn(viewName);
        Long tenantId = viewHasTenantId ? MetaContext.getCurrentTenantId() : null;

        List<Object> whereParams = new ArrayList<>();
        String whereSql = buildWhereClause(request, caps, viewHasTenantId, tenantId, whereParams);
        String orderBySql = buildOrderBy(request, caps);

        String countSql = "SELECT COUNT(*) FROM " + viewName + whereSql;
        log.debug("SqlViewModelExecutor.list count sql: {} params: {}", countSql, whereParams);
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, whereParams.toArray());
        if (total == null) {
            total = 0L;
        }

        if (total == 0L) {
            return PaginationResult.of(Collections.emptyList(), 0L, pageNum, pageSize);
        }

        List<Object> listParams = new ArrayList<>(whereParams);
        listParams.add(pageSize);
        listParams.add(offset);

        String listSql = "SELECT * FROM " + viewName + whereSql + orderBySql + " LIMIT ? OFFSET ?";
        log.debug("SqlViewModelExecutor.list list sql: {} params: {}", listSql, listParams);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(listSql, listParams.toArray());

        return PaginationResult.of(rows, total, pageNum, pageSize);
    }

    @Override
    public Map<String, Object> get(String modelCode, Object primaryKeyValue) {
        ModelDefinition def = requireDefinition(modelCode);
        String viewName = validateIdentifier(def.getSourceRef(), "view name");

        ModelCapabilities caps = def.getCapabilities();
        String pkField = caps != null
            ? caps.resolveDetailKeyField(def.getPrimaryKey())
            : def.getPrimaryKey();
        if (pkField == null || pkField.isBlank()) {
            throw new MetaServiceException(
                "sqlView virtual model missing primaryKey/detailKeyField: " + modelCode);
        }
        validateIdentifier(pkField, "primary key field");

        boolean viewHasTenantId = viewHasTenantIdColumn(viewName);
        List<Object> params = new ArrayList<>();
        // getById's API contract delivers a String recordId regardless of the
        // view's primary-key type; cast the column to text so bigint/uuid/etc.
        // PKs can all be matched against the String without PostgreSQL type
        // resolution failures.
        StringBuilder sql = new StringBuilder("SELECT * FROM ")
            .append(viewName)
            .append(" WHERE CAST(")
            .append(pkField)
            .append(" AS text) = ?");
        params.add(primaryKeyValue == null ? null : primaryKeyValue.toString());

        if (viewHasTenantId) {
            Long tenantId = MetaContext.getCurrentTenantId();
            if (tenantId != null) {
                sql.append(" AND tenant_id = ?");
                params.add(tenantId);
            }
        }
        sql.append(" LIMIT 1");

        log.debug("SqlViewModelExecutor.get sql: {} params: {}", sql, params);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    // --- helpers ---------------------------------------------------------

    private ModelDefinition requireDefinition(String modelCode) {
        ModelDefinition def = metaModelService.getDefinitionByCode(modelCode);
        if (def == null) {
            throw new MetaServiceException("Model definition not found: " + modelCode);
        }
        if (def.getSourceRef() == null || def.getSourceRef().isBlank()) {
            throw new MetaServiceException(
                "sqlView virtual model missing sourceRef: " + modelCode);
        }
        return def;
    }

    private static String validateIdentifier(String id, String label) {
        if (id == null || !SAFE_IDENTIFIER.matcher(id).matches()) {
            throw new MetaServiceException("unsafe SQL identifier (" + label + "): " + id);
        }
        return id;
    }

    private int resolvePageSize(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    /**
     * Probe {@code information_schema.columns} to see if the view carries a
     * {@code tenant_id} column. Result not cached — phase-1 keep it simple;
     * optimisation deferred until profiling shows a hot path.
     */
    private boolean viewHasTenantIdColumn(String viewName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = ? AND column_name = 'tenant_id'",
            Integer.class,
            viewName);
        return count != null && count > 0;
    }

    private String buildWhereClause(DynamicQueryRequest request,
                                    ModelCapabilities caps,
                                    boolean viewHasTenantId,
                                    Long tenantId,
                                    List<Object> outParams) {
        List<String> clauses = new ArrayList<>();

        if (viewHasTenantId && tenantId != null) {
            clauses.add("tenant_id = ?");
            outParams.add(tenantId);
        }

        if (request.getConditions() != null) {
            for (QueryCondition c : request.getConditions()) {
                if (c == null || c.getFieldName() == null || c.getFieldName().isBlank()) {
                    continue;
                }
                String field = c.getFieldName();
                if (!caps.canFilterBy(field)) {
                    throw new MetaServiceException(
                        "Filter on non-whitelisted field for sqlView model: " + field);
                }
                validateIdentifier(field, "filter field");
                clauses.add(renderCondition(field, c, outParams));
            }
        }

        if (clauses.isEmpty()) {
            return "";
        }
        return " WHERE " + String.join(" AND ", clauses);
    }

    private String renderCondition(String field, QueryCondition c, List<Object> outParams) {
        QueryCondition.Operator op = c.getOperator() != null ? c.getOperator() : QueryCondition.Operator.EQ;
        switch (op) {
            case EQ:
                outParams.add(c.getValue());
                return field + " = ?";
            case NE:
                outParams.add(c.getValue());
                return field + " <> ?";
            case GT:
                outParams.add(c.getValue());
                return field + " > ?";
            case GE:
                outParams.add(c.getValue());
                return field + " >= ?";
            case LT:
                outParams.add(c.getValue());
                return field + " < ?";
            case LE:
                outParams.add(c.getValue());
                return field + " <= ?";
            case LIKE:
                outParams.add(c.getValue());
                return field + " LIKE ?";
            case NOT_LIKE:
                outParams.add(c.getValue());
                return field + " NOT LIKE ?";
            case IS_NULL:
                return field + " IS NULL";
            case IS_NOT_NULL:
                return field + " IS NOT NULL";
            case IN:
            case NOT_IN: {
                List<Object> values = c.getValues();
                if (values == null || values.isEmpty()) {
                    throw new MetaServiceException(
                        "IN/NOT_IN on field " + field + " requires non-empty values");
                }
                String placeholders = String.join(", ", Collections.nCopies(values.size(), "?"));
                outParams.addAll(values);
                return field + (op == QueryCondition.Operator.IN ? " IN (" : " NOT IN (") + placeholders + ")";
            }
            case BETWEEN:
            case NOT_BETWEEN: {
                List<Object> values = c.getValues();
                if (values == null || values.size() != 2) {
                    throw new MetaServiceException(
                        "BETWEEN on field " + field + " requires exactly 2 values");
                }
                outParams.add(values.get(0));
                outParams.add(values.get(1));
                return field + (op == QueryCondition.Operator.BETWEEN ? " BETWEEN ? AND ?" : " NOT BETWEEN ? AND ?");
            }
            default:
                throw new MetaServiceException("Unsupported operator for sqlView: " + op);
        }
    }

    private String buildOrderBy(DynamicQueryRequest request, ModelCapabilities caps) {
        List<SortField> sorts = request.getSortFields();
        if (sorts == null || sorts.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (SortField s : sorts) {
            if (s == null || s.getFieldName() == null || s.getFieldName().isBlank()) {
                continue;
            }
            String field = s.getFieldName();
            if (!caps.canSortBy(field)) {
                throw new MetaServiceException(
                    "Sort on non-whitelisted field for sqlView model: " + field);
            }
            validateIdentifier(field, "sort field");
            String dir = (s.getDirection() == SortField.SortDirection.DESC) ? "DESC" : "ASC";
            parts.add(field + " " + dir);
        }
        if (parts.isEmpty()) {
            return "";
        }
        return " ORDER BY " + String.join(", ", parts);
    }
}
