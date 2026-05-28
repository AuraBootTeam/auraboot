package com.auraboot.framework.semantic.parser;

import com.auraboot.framework.semantic.dto.*;
import com.auraboot.framework.semantic.exception.SemanticValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Business-rule validator for a parsed {@link SemanticModelDTO}.
 *
 * <p>Runs after {@link SemanticYamlParser} (which handles structural / JSON Schema
 * concerns). Validates rules that JSON Schema cannot express:
 *
 * <ul>
 *   <li><b>Security</b>: SQL injection denylist in {@code metric.filter} and
 *       {@code access_policy.sql_filter} ({@code ;}, {@code --}, {@code DROP}, {@code UNION}, ...)</li>
 *   <li><b>Uniqueness</b>: duplicate code within entities / dimensions / measures / metrics</li>
 *   <li><b>Cardinality</b>: at most one entity with {@code type=primary}; at most one
 *       dimension with {@code primary_time=true}</li>
 *   <li><b>References</b>: {@code primary_entity}, {@code measure} refs, {@code derived.expr}
 *       placeholders, {@code ratio.{numerator,denominator}}, {@code conversion} entity</li>
 *   <li><b>Measure shape</b>: each measure has at least one of {@code field_ref} or {@code expr}</li>
 * </ul>
 *
 * <p>All violations throw {@link SemanticValidationException} with a stable
 * {@code errorCode} suitable for both i18n and API error contracts.
 */
@Slf4j
@Component
public class SemanticValidator {

    private static final Pattern SQL_DENYLIST_PATTERN = Pattern.compile(
            "(?i)(--|/\\*|\\*/|;\\s*(\\w|$)|\\bDROP\\b|\\bTRUNCATE\\b|\\bDELETE\\b|"
                    + "\\bUPDATE\\b|\\bINSERT\\b|\\bALTER\\b|\\bGRANT\\b|\\bREVOKE\\b|"
                    + "\\bUNION\\b|\\bEXEC\\b|\\bEXECUTE\\b|\\bxp_)"
    );

    /** Allowed user-attribute placeholder pattern: {@code {user.<snake_case>}}. */
    private static final Pattern USER_PLACEHOLDER_PATTERN = Pattern.compile(
            "\\{user\\.[a-z][a-z0-9_]*\\}");

    /** Pattern allowed inside derived metric expressions: {@code {metric_code}}. */
    private static final Pattern METRIC_PLACEHOLDER_PATTERN = Pattern.compile(
            "\\{([a-z][a-z0-9_]*)\\}");

    public void validate(SemanticModelDTO dto) {
        validateHeader(dto);
        validateEntities(dto);
        validateDimensions(dto);
        validateMeasures(dto);
        validateMetrics(dto);
        validateAccessPolicies(dto);
    }

    // -- header --------------------------------------------------------------

    private void validateHeader(SemanticModelDTO dto) {
        if (dto.getSemanticModel() == null) {
            throw new SemanticValidationException("MISSING_HEADER", "semantic_model header missing");
        }
        String primaryEntity = dto.getSemanticModel().getPrimaryEntity();
        boolean found = dto.getEntities().stream()
                .anyMatch(e -> primaryEntity.equals(e.getName()));
        if (!found) {
            throw new SemanticValidationException(
                    "MISSING_REFERENCE",
                    "primary_entity '" + primaryEntity + "' not declared in entities[]");
        }
    }

    // -- entities ------------------------------------------------------------

    private void validateEntities(SemanticModelDTO dto) {
        assertUniqueCodes(dto.getEntities().stream().map(EntityDTO::getName).toList(),
                "DUPLICATE_CODE", "entity name");

        long primaries = dto.getEntities().stream()
                .filter(e -> "primary".equals(e.getType()))
                .count();
        if (primaries > 1) {
            throw new SemanticValidationException(
                    "ENTITY_TYPE_INVALID",
                    "at most one entity may have type=primary (found " + primaries + ")");
        }
    }

    // -- dimensions ----------------------------------------------------------

    private void validateDimensions(SemanticModelDTO dto) {
        assertUniqueCodes(dto.getDimensions().stream().map(DimensionDTO::getCode).toList(),
                "DUPLICATE_CODE", "dimension code");

        long primaryTimes = dto.getDimensions().stream()
                .filter(d -> Boolean.TRUE.equals(d.getPrimaryTime()))
                .count();
        if (primaryTimes > 1) {
            throw new SemanticValidationException(
                    "MULTIPLE_PRIMARY_TIME",
                    "at most one dimension may set primary_time=true (found " + primaryTimes + ")");
        }
    }

    // -- measures ------------------------------------------------------------

    private void validateMeasures(SemanticModelDTO dto) {
        assertUniqueCodes(dto.getMeasures().stream().map(MeasureDTO::getCode).toList(),
                "DUPLICATE_CODE", "measure code");

        for (MeasureDTO m : dto.getMeasures()) {
            boolean hasField = m.getFieldRef() != null && !m.getFieldRef().isBlank();
            boolean hasExpr = m.getExpr() != null && !m.getExpr().isBlank();
            if (!hasField && !hasExpr) {
                throw new SemanticValidationException(
                        "MEASURE_MISSING_EXPR_OR_FIELD",
                        "measure '" + m.getCode() + "' must have at least one of field_ref or expr");
            }
            if (hasExpr) {
                assertNoSqlInjection(m.getExpr(), "measure[" + m.getCode() + "].expr");
            }
        }
    }

    // -- metrics -------------------------------------------------------------

    private void validateMetrics(SemanticModelDTO dto) {
        assertUniqueCodes(dto.getMetrics().stream().map(MetricDTO::getCode).toList(),
                "DUPLICATE_CODE", "metric code");

        Set<String> measureCodes = new HashSet<>();
        dto.getMeasures().forEach(m -> measureCodes.add(m.getCode()));
        Set<String> metricCodes = new HashSet<>();
        dto.getMetrics().forEach(m -> metricCodes.add(m.getCode()));
        Set<String> entityNames = new HashSet<>();
        dto.getEntities().forEach(e -> entityNames.add(e.getName()));

        for (MetricDTO mt : dto.getMetrics()) {
            if (mt.getFilter() != null && !mt.getFilter().isBlank()) {
                assertNoSqlInjection(mt.getFilter(), "metric[" + mt.getCode() + "].filter");
            }
            String type = mt.getType().toLowerCase(Locale.ROOT);
            Map<String, Object> params = mt.getTypeParams();
            switch (type) {
                case "simple" -> requireMeasureRef(params, "measure", measureCodes, mt.getCode());
                case "ratio" -> {
                    requireMeasureRef(params, "numerator", measureCodes, mt.getCode());
                    requireMeasureRef(params, "denominator", measureCodes, mt.getCode());
                }
                case "cumulative" -> requireMeasureRef(params, "measure", measureCodes, mt.getCode());
                case "derived" -> {
                    String expr = String.valueOf(params.get("expr"));
                    assertNoSqlInjection(expr, "metric[" + mt.getCode() + "].type_params.expr");
                    java.util.regex.Matcher matcher = METRIC_PLACEHOLDER_PATTERN.matcher(expr);
                    while (matcher.find()) {
                        String referenced = matcher.group(1);
                        if (!metricCodes.contains(referenced) && !measureCodes.contains(referenced)) {
                            throw new SemanticValidationException(
                                    "MISSING_REFERENCE",
                                    "metric[" + mt.getCode() + "].type_params.expr references unknown name '"
                                            + referenced + "'");
                        }
                    }
                }
                case "conversion" -> {
                    requireMeasureRef(params, "base_measure", measureCodes, mt.getCode());
                    requireMeasureRef(params, "conversion_measure", measureCodes, mt.getCode());
                    String entity = String.valueOf(params.get("entity"));
                    if (!entityNames.contains(entity)) {
                        throw new SemanticValidationException(
                                "MISSING_REFERENCE",
                                "metric[" + mt.getCode() + "] conversion.entity '" + entity
                                        + "' not declared in entities[]");
                    }
                }
                default -> {
                    // JSON Schema enum should already block; defensive only
                    throw new SemanticValidationException(
                            "INVALID_METRIC_TYPE",
                            "metric[" + mt.getCode() + "] unknown type: " + type);
                }
            }
        }
    }

    private void requireMeasureRef(Map<String, Object> params, String key,
                                    Set<String> known, String metricCode) {
        Object raw = params.get(key);
        if (raw == null) {
            throw new SemanticValidationException(
                    "MISSING_PARAM",
                    "metric[" + metricCode + "].type_params." + key + " missing");
        }
        String code = String.valueOf(raw);
        if (!known.contains(code)) {
            throw new SemanticValidationException(
                    "MISSING_REFERENCE",
                    "metric[" + metricCode + "].type_params." + key + " refs unknown measure '" + code + "'");
        }
    }

    // -- access policies -----------------------------------------------------

    private void validateAccessPolicies(SemanticModelDTO dto) {
        List<AccessPolicyDTO> policies = dto.getAccessPolicies();
        if (policies == null) {
            return;
        }
        assertUniqueCodes(policies.stream().map(AccessPolicyDTO::getAccessGrant).toList(),
                "DUPLICATE_CODE", "access_grant");

        Set<String> dimCodes = new HashSet<>();
        dto.getDimensions().forEach(d -> dimCodes.add(d.getCode()));

        for (AccessPolicyDTO p : policies) {
            if (p.getSqlFilter() != null && !p.getSqlFilter().isBlank()) {
                assertNoSqlInjection(p.getSqlFilter(), "access_policy[" + p.getAccessGrant() + "].sql_filter");
                // Reject placeholders that are NOT {user.<attr>} form
                String stripped = USER_PLACEHOLDER_PATTERN.matcher(p.getSqlFilter()).replaceAll("");
                if (stripped.contains("{")) {
                    throw new SemanticValidationException(
                            "ACCESS_POLICY_INVALID_PLACEHOLDER",
                            "access_policy[" + p.getAccessGrant() + "].sql_filter may only use {user.<attr>} placeholders");
                }
            }
            if (p.getTargetDimensions() != null) {
                for (String d : p.getTargetDimensions()) {
                    if (!dimCodes.contains(d)) {
                        throw new SemanticValidationException(
                                "MISSING_REFERENCE",
                                "access_policy[" + p.getAccessGrant() + "].target_dimensions: '" + d
                                        + "' not a declared dimension");
                    }
                }
            }
        }
    }

    // -- helpers -------------------------------------------------------------

    private void assertNoSqlInjection(String sql, String location) {
        if (SQL_DENYLIST_PATTERN.matcher(sql).find()) {
            log.warn("SQL injection denylist hit at {}: {}", location, sql);
            throw new SemanticValidationException(
                    "SQL_INJECTION_DETECTED",
                    location + " contains forbidden SQL token (matches denylist)");
        }
    }

    private void assertUniqueCodes(List<String> codes, String errorCode, String kind) {
        Set<String> seen = new HashSet<>();
        for (String c : codes) {
            if (!seen.add(c)) {
                throw new SemanticValidationException(
                        errorCode, "duplicate " + kind + ": '" + c + "'");
            }
        }
    }
}
