package com.auraboot.framework.dataquality.ge;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.dataquality.ge.entity.AbDataQualityExpectationSuite;
import com.auraboot.framework.dataquality.ge.entity.AbDataQualityValidationRun;
import com.auraboot.framework.dataquality.ge.mapper.AbDataQualityValidationRunMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.QueryBuilderDTO;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.QueryBuilderReadProtection;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import org.springframework.security.access.AccessDeniedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes parameterized validations on a registered source using the existing
 * model-read, field-inference and tenant/row-scope query protection. The SQL
 * reads and final validation-run insert participate in the outer transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GreatExpectationsValidator {

    /** Matches valid SQL identifiers: letter or underscore, then letters/digits/underscores. */
    static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final MetaModelMapper modelMapper;
    private final MetaModelService models;
    private final QueryBuilderReadProtection sourceProtection;
    private final PermissionEvaluator permissions;
    private final AbDataQualityValidationRunMapper runMapper;
    private final ExpectationsParser parser;
    private final ObjectMapper objectMapper;

    /**
     * Run all expectations in {@code suite} against its {@code dataset_name} table,
     * persist a {@link AbDataQualityValidationRun}, and return the run.
     *
     * @param tenantId tenant context
     * @param suite    the expectation suite to validate
     * @return the persisted run record (with pass/fail counts populated)
     * @throws IllegalArgumentException if dataset_name or a column name fails identifier validation
     */
    @Transactional
    public AbDataQualityValidationRun validate(Long tenantId, AbDataQualityExpectationSuite suite) {
        List<ExpectationConfig> expectations = parser.parse(suite.getExpectationsJson());
        Source source = prepareSource(tenantId, suite, expectations);
        String datasetName = suite.getDatasetName();

        Instant started = Instant.now();
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;

        for (ExpectationConfig exp : expectations) {
            ExpectationResult r = evaluate(datasetName, exp, source);
            results.add(r.toMap());
            if (r.passed()) {
                passed++;
            } else {
                failed++;
            }
        }

        AbDataQualityValidationRun run = new AbDataQualityValidationRun();
        run.setPid(UlidGenerator.generate());
        run.setTenantId(tenantId);
        run.setSuitePid(suite.getPid());
        run.setDatasetName(datasetName);
        run.setTotalExpectations(expectations.size());
        run.setPassed(passed);
        run.setFailed(failed);
        run.setResultsJson(toJson(results));
        run.setStartedAt(started);
        run.setFinishedAt(Instant.now());
        runMapper.insert(run);

        log.info("GE validation run: suite={} dataset={} total={} passed={} failed={}",
                suite.getPid(), datasetName, expectations.size(), passed, failed);
        return run;
    }

    // -----------------------------------------------------------------------
    // Per-expectation evaluation
    // -----------------------------------------------------------------------

    private ExpectationResult evaluate(String dataset, ExpectationConfig exp, Source source) {
        return switch (exp.expectationType()) {
            case ExpectationConfig.NOT_NULL -> evalNotNull(dataset, exp, source);
            case ExpectationConfig.COLUMN_LENGTH -> evalColumnLength(dataset, exp, source);
            case ExpectationConfig.MATCH_REGEX -> evalMatchRegex(dataset, exp, source);
            case ExpectationConfig.TABLE_ROW_COUNT -> evalRowCount(dataset, exp, source);
            case ExpectationConfig.IN_SET -> evalInSet(dataset, exp, source);
            case ExpectationConfig.PAIR_A_GT_B -> evalPairAGtB(dataset, exp, source);
            default -> throw new IllegalStateException("Unhandled expectation type: " + exp.expectationType());
        };
    }

    /**
     * expect_column_values_to_not_be_null:
     * {@code SELECT COUNT(*) FROM dataset WHERE col IS NULL}
     * Pass if count = 0.
     */
    private ExpectationResult evalNotNull(String dataset, ExpectationConfig exp, Source source) {
        String col = requireValidColumn(exp.column());
        String sql = "SELECT COUNT(*) AS cnt FROM " + dataset + " WHERE " + col + " IS NULL";
        long nullCount = countQuery(source, sql, Map.of());
        boolean passed = nullCount == 0;
        return new ExpectationResult(exp.expectationType(), exp.column(), passed, nullCount,
                "null_count=" + nullCount);
    }

    /**
     * expect_column_value_lengths_to_be_between:
     * {@code SELECT COUNT(*) FROM dataset WHERE LENGTH(col) NOT BETWEEN minValue AND maxValue}
     * Pass if count = 0.
     */
    private ExpectationResult evalColumnLength(String dataset, ExpectationConfig exp, Source source) {
        String col = requireValidColumn(exp.column());
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS cnt FROM ").append(dataset)
                .append(" WHERE LENGTH(").append(col).append(")");
        if (exp.minValue() != null && exp.maxValue() != null) {
            sql.append(" NOT BETWEEN #{params.minVal} AND #{params.maxVal}");
            params.put("minVal", exp.minValue());
            params.put("maxVal", exp.maxValue());
        } else if (exp.minValue() != null) {
            sql.append(" < #{params.minVal}");
            params.put("minVal", exp.minValue());
        } else if (exp.maxValue() != null) {
            sql.append(" > #{params.maxVal}");
            params.put("maxVal", exp.maxValue());
        } else {
            // No bounds: trivially passes (nothing to check).
            return new ExpectationResult(exp.expectationType(), exp.column(), true, 0L, "no_bounds");
        }
        long violationCount = countQuery(source, sql.toString(), params);
        boolean passed = violationCount == 0;
        return new ExpectationResult(exp.expectationType(), exp.column(), passed, violationCount,
                "violation_count=" + violationCount);
    }

    /**
     * expect_column_values_to_match_regex:
     * PostgreSQL {@code ~} operator: {@code SELECT COUNT(*) WHERE col !~ 'regex'}
     * Pass if count = 0.
     */
    private ExpectationResult evalMatchRegex(String dataset, ExpectationConfig exp, Source source) {
        String col = requireValidColumn(exp.column());
        // Regex is passed as a JDBC parameter to prevent injection.
        String sql = "SELECT COUNT(*) AS cnt FROM " + dataset
                + " WHERE " + col + " IS NOT NULL AND " + col + " !~ #{params.regex}";
        Map<String, Object> params = Map.of("regex", exp.regex());
        long violationCount = countQuery(source, sql, params);
        boolean passed = violationCount == 0;
        return new ExpectationResult(exp.expectationType(), exp.column(), passed, violationCount,
                "regex_mismatch_count=" + violationCount);
    }

    /**
     * expect_table_row_count_to_be_between:
     * {@code SELECT COUNT(*) FROM dataset}
     * Pass if result is between minValue and maxValue (inclusive).
     */
    private ExpectationResult evalRowCount(String dataset, ExpectationConfig exp, Source source) {
        String sql = "SELECT COUNT(*) AS cnt FROM " + dataset;
        long count = countQuery(source, sql, Map.of());
        boolean passed = true;
        if (exp.minValue() != null && count < exp.minValue()) passed = false;
        if (exp.maxValue() != null && count > exp.maxValue()) passed = false;
        return new ExpectationResult(exp.expectationType(), null, passed, count,
                "row_count=" + count);
    }

    /**
     * expect_column_values_to_be_in_set:
     * {@code SELECT COUNT(*) FROM dataset WHERE col IS NOT NULL AND col NOT IN (...)}
     * Pass if count = 0.
     * NULL values are skipped (GE default behaviour for not_null is a separate expectation).
     */
    private ExpectationResult evalInSet(String dataset, ExpectationConfig exp, Source source) {
        String col = requireValidColumn(exp.column());
        List<String> valueSet = exp.valueSet();
        if (valueSet == null || valueSet.isEmpty()) {
            // Empty set: no values are ever in the set → every non-null row fails.
            // This is the correct GE semantics.
            String countSql = "SELECT COUNT(*) AS cnt FROM " + dataset + " WHERE " + col + " IS NOT NULL";
            long nonNullCount = countQuery(source, countSql, Map.of());
            boolean passed = nonNullCount == 0;
            return new ExpectationResult(exp.expectationType(), exp.column(), passed, nonNullCount,
                    "violation_count=" + nonNullCount + " (empty_set)");
        }

        // Build parameterized IN clause: col NOT IN (#{params.v0}, #{params.v1}, ...)
        Map<String, Object> params = new HashMap<>();
        List<String> placeholders = new ArrayList<>();
        for (int i = 0; i < valueSet.size(); i++) {
            String key = "v" + i;
            params.put(key, valueSet.get(i));
            placeholders.add("#{params." + key + "}");
        }
        String inClause = placeholders.stream().collect(Collectors.joining(", ", "(", ")"));
        String sql = "SELECT COUNT(*) AS cnt FROM " + dataset
                + " WHERE " + col + " IS NOT NULL AND " + col + " NOT IN " + inClause;
        long violationCount = countQuery(source, sql, params);
        boolean passed = violationCount == 0;
        return new ExpectationResult(exp.expectationType(), exp.column(), passed, violationCount,
                "violation_count=" + violationCount);
    }

    /**
     * expect_column_pair_values_a_to_be_greater_than_b:
     * {@code SELECT COUNT(*) FROM dataset WHERE NOT (colA > colB)}
     * Pass if count = 0.
     */
    private ExpectationResult evalPairAGtB(String dataset, ExpectationConfig exp, Source source) {
        String colA = requireValidColumn(exp.columnA());
        String colB = requireValidColumn(exp.columnB());
        String sql = "SELECT COUNT(*) AS cnt FROM " + dataset
                + " WHERE NOT (" + colA + " > " + colB + ")";
        long violationCount = countQuery(source, sql, Map.of());
        boolean passed = violationCount == 0;
        return new ExpectationResult(exp.expectationType(), colA + "," + colB, passed, violationCount,
                "violation_count=" + violationCount);
    }

    // -----------------------------------------------------------------------
    // SQL helpers
    // -----------------------------------------------------------------------

    /** Check the source before creating a suite or returning its stored results. */
    public void authorizeSuite(Long tenantId, AbDataQualityExpectationSuite suite) {
        prepareSource(tenantId, suite, parser.parse(suite.getExpectationsJson()));
    }

    private record Source(QueryBuilderReadProtection.Plan plan, boolean softDelete) { }

    private Source prepareSource(Long tenantId, AbDataQualityExpectationSuite suite,
                                 List<ExpectationConfig> expectations) {
        if (!MetaContext.exists() || MetaContext.getCurrentUserId() == null
                || tenantId == null || !tenantId.equals(MetaContext.getCurrentTenantId())
                || !tenantId.equals(suite.getTenantId())) {
            throw new AccessDeniedException("Validation requires the current suite tenant and identity");
        }
        String dataset = suite.getDatasetName();
        validateIdentifier(dataset, "dataset_name");
        Set<String> inputs = new LinkedHashSet<>();
        for (ExpectationConfig exp : expectations) {
            for (String column : new String[] {exp.column(), exp.columnA(), exp.columnB()}) {
                if (column != null) inputs.add(requireValidColumn(column));
            }
        }
        // A suite stores a local relation name, not a connector or external-source grant.
        var candidates = modelMapper.findCurrentForTenant(tenantId).stream().filter(model -> {
            String relation = "sqlView".equals(model.getSourceType()) ? model.getSourceRef() : model.getTableName();
            if (relation == null || relation.isBlank()) relation = SystemFieldConstants.generateTableName(model.getCode());
            return dataset.equalsIgnoreCase(relation);
        }).toList();
        if (candidates.size() != 1) {
            throw new AccessDeniedException("Validation source model is unknown or ambiguous");
        }
        String modelCode = candidates.getFirst().getCode();
        Long memberId = MetaContext.getCurrentMemberId();
        if (memberId == null) memberId = MetaContext.getCurrentUserId();
        if (!permissions.canAction(memberId, modelCode, "read")) {
            throw new AccessDeniedException("Validation source read permission is required");
        }
        Map<String, String> columns = new LinkedHashMap<>();
        for (var field : models.getModelFields(modelCode)) {
            // Host-column inference depends on every virtual field stored in that host.
            String column = field.isJsonbVirtual() ? field.getJsonbColumn() : field.getColumnName();
            if (column == null || column.isBlank()) column = field.getCode();
            columns.put(field.getCode(), column);
        }
        Set<String> inferenceFields = new LinkedHashSet<>();
        for (String input : inputs) {
            Set<String> aliases = columns.entrySet().stream().filter(entry -> input.equals(entry.getValue()))
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
            if (aliases.isEmpty()) throw new AccessDeniedException("Validation columns must be registered");
            inferenceFields.addAll(aliases);
        }
        QueryBuilderReadProtection.Plan plan;
        if (inferenceFields.isEmpty()) {
            QueryBuilderDTO dto = new QueryBuilderDTO();
            dto.setModelCode(modelCode);
            plan = sourceProtection.prepare(dto, columns, dataset);
        } else {
            plan = sourceProtection.prepareComparison(modelCode, inferenceFields, columns, dataset);
        }
        return new Source(plan, columns.containsValue("deleted_flag"));
    }

    private long countQuery(Source source, String sql, Map<String, Object> params) {
        if (source.softDelete()) {
            sql += sql.contains(" WHERE ") ? " AND " : " WHERE ";
            sql += "(deleted_flag = FALSE OR deleted_flag IS NULL)";
        }
        return sourceProtection.executeCount(source.plan(), sql, params);
    }

    // -----------------------------------------------------------------------
    // Identifier validation
    // -----------------------------------------------------------------------

    /**
     * Validates a SQL identifier (table/column name) against the whitelist pattern.
     *
     * @throws IllegalArgumentException if the identifier is invalid
     */
    static void validateIdentifier(String name, String context) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("SQL identifier for '" + context + "' must not be blank");
        }
        if (!IDENTIFIER_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "SQL identifier for '" + context + "' contains illegal characters: '" + name + "'");
        }
    }

    private static String requireValidColumn(String colName) {
        validateIdentifier(colName, "column");
        return colName;
    }

    // -----------------------------------------------------------------------
    // JSON helper
    // -----------------------------------------------------------------------

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize results to JSON", e);
        }
    }

    // -----------------------------------------------------------------------
    // Inner result type
    // -----------------------------------------------------------------------

    private record ExpectationResult(
            String expectationType,
            String column,
            boolean passed,
            long actualValue,
            String details
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("expectation_type", expectationType);
            m.put("column", column);
            m.put("passed", passed);
            m.put("actual_value", actualValue);
            m.put("details", details);
            return m;
        }
    }
}
