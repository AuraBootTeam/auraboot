package com.auraboot.framework.dataquality.ge;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.dataquality.ge.entity.AbDataQualityExpectationSuite;
import com.auraboot.framework.dataquality.ge.entity.AbDataQualityValidationRun;
import com.auraboot.framework.dataquality.ge.mapper.AbDataQualityValidationRunMapper;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
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
import java.util.stream.Collectors;

/**
 * Executes Great Expectations-style validations against a real database table.
 *
 * <p>For each expectation in the suite, one or more parameterized SQL queries
 * are executed via {@link DynamicDataMapper}. Results are collected and
 * persisted in {@code ab_dataquality_validation_run}.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>{@code dataset_name} and column names are validated against
 *       {@link #IDENTIFIER_PATTERN} (same pattern as
 *       {@code AggregateQueryServiceImpl}) before being interpolated into SQL.
 *       Any value that fails validation causes {@link IllegalArgumentException}.</li>
 *   <li>User-supplied literal values ({@code value_set}, regex, row-count bounds)
 *       are passed as JDBC parameters, never interpolated.</li>
 * </ul>
 *
 * <h3>Transaction model</h3>
 * <p>The validation SELECT queries are read-only; only the final
 * {@code INSERT INTO ab_dataquality_validation_run} is transactional.
 * We use {@link org.springframework.transaction.annotation.Transactional} for
 * the outer method and run the SELECT queries outside the write transaction via
 * the injected {@code dynamicDataMapper} which operates with default propagation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GreatExpectationsValidator {

    /** Matches valid SQL identifiers: letter or underscore, then letters/digits/underscores. */
    static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final DynamicDataMapper dynamicDataMapper;
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
        String datasetName = suite.getDatasetName();
        validateIdentifier(datasetName, "dataset_name");

        List<ExpectationConfig> expectations = parser.parse(suite.getExpectationsJson());

        Instant started = Instant.now();
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;

        for (ExpectationConfig exp : expectations) {
            ExpectationResult r = evaluate(datasetName, exp);
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

    private ExpectationResult evaluate(String dataset, ExpectationConfig exp) {
        return switch (exp.expectationType()) {
            case ExpectationConfig.NOT_NULL -> evalNotNull(dataset, exp);
            case ExpectationConfig.COLUMN_LENGTH -> evalColumnLength(dataset, exp);
            case ExpectationConfig.MATCH_REGEX -> evalMatchRegex(dataset, exp);
            case ExpectationConfig.TABLE_ROW_COUNT -> evalRowCount(dataset, exp);
            case ExpectationConfig.IN_SET -> evalInSet(dataset, exp);
            case ExpectationConfig.PAIR_A_GT_B -> evalPairAGtB(dataset, exp);
            default -> throw new IllegalStateException("Unhandled expectation type: " + exp.expectationType());
        };
    }

    /**
     * expect_column_values_to_not_be_null:
     * {@code SELECT COUNT(*) FROM dataset WHERE col IS NULL}
     * Pass if count = 0.
     */
    private ExpectationResult evalNotNull(String dataset, ExpectationConfig exp) {
        String col = requireValidColumn(exp.column());
        String sql = "SELECT COUNT(*) AS cnt FROM " + dataset + " WHERE " + col + " IS NULL";
        long nullCount = countQuery(sql, Map.of());
        boolean passed = nullCount == 0;
        return new ExpectationResult(exp.expectationType(), exp.column(), passed, nullCount,
                "null_count=" + nullCount);
    }

    /**
     * expect_column_value_lengths_to_be_between:
     * {@code SELECT COUNT(*) FROM dataset WHERE LENGTH(col) NOT BETWEEN minValue AND maxValue}
     * Pass if count = 0.
     */
    private ExpectationResult evalColumnLength(String dataset, ExpectationConfig exp) {
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
        long violationCount = countQuery(sql.toString(), params);
        boolean passed = violationCount == 0;
        return new ExpectationResult(exp.expectationType(), exp.column(), passed, violationCount,
                "violation_count=" + violationCount);
    }

    /**
     * expect_column_values_to_match_regex:
     * PostgreSQL {@code ~} operator: {@code SELECT COUNT(*) WHERE col !~ 'regex'}
     * Pass if count = 0.
     */
    private ExpectationResult evalMatchRegex(String dataset, ExpectationConfig exp) {
        String col = requireValidColumn(exp.column());
        // Regex is passed as a JDBC parameter to prevent injection.
        String sql = "SELECT COUNT(*) AS cnt FROM " + dataset
                + " WHERE " + col + " IS NOT NULL AND " + col + " !~ #{params.regex}";
        Map<String, Object> params = Map.of("regex", exp.regex());
        long violationCount = countQuery(sql, params);
        boolean passed = violationCount == 0;
        return new ExpectationResult(exp.expectationType(), exp.column(), passed, violationCount,
                "regex_mismatch_count=" + violationCount);
    }

    /**
     * expect_table_row_count_to_be_between:
     * {@code SELECT COUNT(*) FROM dataset}
     * Pass if result is between minValue and maxValue (inclusive).
     */
    private ExpectationResult evalRowCount(String dataset, ExpectationConfig exp) {
        String sql = "SELECT COUNT(*) AS cnt FROM " + dataset;
        long count = countQuery(sql, Map.of());
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
    private ExpectationResult evalInSet(String dataset, ExpectationConfig exp) {
        String col = requireValidColumn(exp.column());
        List<String> valueSet = exp.valueSet();
        if (valueSet == null || valueSet.isEmpty()) {
            // Empty set: no values are ever in the set → every non-null row fails.
            // This is the correct GE semantics.
            String countSql = "SELECT COUNT(*) AS cnt FROM " + dataset + " WHERE " + col + " IS NOT NULL";
            long nonNullCount = countQuery(countSql, Map.of());
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
        long violationCount = countQuery(sql, params);
        boolean passed = violationCount == 0;
        return new ExpectationResult(exp.expectationType(), exp.column(), passed, violationCount,
                "violation_count=" + violationCount);
    }

    /**
     * expect_column_pair_values_a_to_be_greater_than_b:
     * {@code SELECT COUNT(*) FROM dataset WHERE NOT (colA > colB)}
     * Pass if count = 0.
     */
    private ExpectationResult evalPairAGtB(String dataset, ExpectationConfig exp) {
        String colA = requireValidColumn(exp.columnA());
        String colB = requireValidColumn(exp.columnB());
        String sql = "SELECT COUNT(*) AS cnt FROM " + dataset
                + " WHERE NOT (" + colA + " > " + colB + ")";
        long violationCount = countQuery(sql, Map.of());
        boolean passed = violationCount == 0;
        return new ExpectationResult(exp.expectationType(), colA + "," + colB, passed, violationCount,
                "violation_count=" + violationCount);
    }

    // -----------------------------------------------------------------------
    // SQL helpers
    // -----------------------------------------------------------------------

    private long countQuery(String sql, Map<String, Object> params) {
        // Use countByQueryWithoutTenant: the dataset may be any user table, not an
        // AuraBoot multi-tenant table. Tenant isolation is the responsibility of
        // the caller who selects which suite to run. If the dataset does contain
        // tenant_id, the user's expectation suite should include a filter condition.
        Long count = dynamicDataMapper.countByQueryWithoutTenant(sql, params);
        return count != null ? count : 0L;
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
