package com.auraboot.framework.meta.security;

import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.dto.QuerySecurityValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlInjectionProtectorTest {

    private SqlInjectionProtector protector;

    @BeforeEach
    void setUp() {
        protector = new SqlInjectionProtector();
    }

    private QueryCondition cond(String field, QueryCondition.Operator op, Object value) {
        return QueryCondition.builder()
                .fieldName(field)
                .operator(op)
                .value(value)
                .build();
    }

    @Test
    void validateQueryConditions_null_returnsValid() {
        QuerySecurityValidationResult result = protector.validateQueryConditions(null);
        assertThat(result.getValid()).isTrue();
        assertThat(result.getRiskLevel()).isEqualTo(QuerySecurityValidationResult.SecurityRiskLevel.LOW);
    }

    @Test
    void validateQueryConditions_empty_returnsValid() {
        QuerySecurityValidationResult result = protector.validateQueryConditions(List.of());
        assertThat(result.getValid()).isTrue();
    }

    @Test
    void validateQueryConditions_safeCondition_isValid() {
        QuerySecurityValidationResult result = protector.validateQueryConditions(List.of(
                cond("name", QueryCondition.Operator.EQ, "alice")
        ));
        assertThat(result.getValid()).isTrue();
    }

    @Test
    void validateQueryConditions_emptyFieldName_addsError() {
        QuerySecurityValidationResult result = protector.validateQueryConditions(List.of(
                cond("", QueryCondition.Operator.EQ, "x")
        ));
        assertThat(result.getValid()).isFalse();
        assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    void validateQueryConditions_unsafeFieldName_high() {
        QuerySecurityValidationResult result = protector.validateQueryConditions(List.of(
                cond("name';drop", QueryCondition.Operator.EQ, "x")
        ));
        assertThat(result.getValid()).isFalse();
        assertThat(result.getRiskLevel())
                .isIn(QuerySecurityValidationResult.SecurityRiskLevel.HIGH,
                      QuerySecurityValidationResult.SecurityRiskLevel.CRITICAL);
    }

    @Test
    void validateQueryConditions_sqlKeywordFieldName_medium() {
        QuerySecurityValidationResult result = protector.validateQueryConditions(List.of(
                cond("SELECT", QueryCondition.Operator.EQ, "x")
        ));
        assertThat(result.getSecurityIssues()).anyMatch(i -> "SQL_KEYWORD_FIELD".equals(i.getType()));
    }

    @Test
    void validateQueryConditions_longFieldName_medium() {
        String longName = "a".repeat(150);
        QuerySecurityValidationResult result = protector.validateQueryConditions(List.of(
                cond(longName, QueryCondition.Operator.EQ, "x")
        ));
        assertThat(result.getSecurityIssues()).anyMatch(i -> "FIELD_NAME_TOO_LONG".equals(i.getType()));
    }

    @Test
    void validateQueryConditions_injectionValue_critical() {
        QuerySecurityValidationResult result = protector.validateQueryConditions(List.of(
                cond("name", QueryCondition.Operator.EQ, "1' OR '1'='1")
        ));
        assertThat(result.getValid()).isFalse();
        assertThat(result.getSecurityIssues()).anyMatch(i -> "SQL_INJECTION_PATTERN".equals(i.getType()));
    }

    @Test
    void validateQueryConditions_longValue_warningOrIssue() {
        String longValue = "x".repeat(1500);
        QuerySecurityValidationResult result = protector.validateQueryConditions(List.of(
                cond("name", QueryCondition.Operator.EQ, longValue)
        ));
        assertThat(result.getSecurityIssues()).anyMatch(i -> "VALUE_TOO_LONG".equals(i.getType()));
    }

    @Test
    void validateQueryConditions_nullValue_skipped() {
        QuerySecurityValidationResult result = protector.validateQueryConditions(List.of(
                cond("name", QueryCondition.Operator.IS_NULL, null)
        ));
        assertThat(result.getValid()).isTrue();
    }

    @Test
    void validateQueryConditions_nullCondition_isIgnored() {
        java.util.List<QueryCondition> conds = new java.util.ArrayList<>();
        conds.add(null);
        QuerySecurityValidationResult result = protector.validateQueryConditions(conds);
        assertThat(result.getValid()).isTrue();
    }

    @Test
    void validateQueryConditions_specialCharsValue_addsWarning() {
        QuerySecurityValidationResult result = protector.validateQueryConditions(List.of(
                cond("name", QueryCondition.Operator.EQ, "<bob>")
        ));
        assertThat(result.getWarnings()).isNotEmpty();
    }

    @Test
    void sanitizeValue_blankPassthrough() {
        assertThat(protector.sanitizeValue(null)).isNull();
        assertThat(protector.sanitizeValue("")).isEqualTo("");
    }

    @Test
    void sanitizeValue_removesDangerousChars() {
        String sanitized = protector.sanitizeValue("a';drop\"\\");
        assertThat(sanitized).doesNotContain("'", "\"", ";", "\\");
    }

    @Test
    void sanitizeValue_truncatesTooLong() {
        String input = "x".repeat(2000);
        assertThat(protector.sanitizeValue(input)).hasSize(1000);
    }

    @Test
    void isSafeValue_blank_true() {
        assertThat(protector.isSafeValue(null)).isTrue();
        assertThat(protector.isSafeValue("")).isTrue();
    }

    @Test
    void isSafeValue_normal_true() {
        assertThat(protector.isSafeValue("alice")).isTrue();
    }

    @Test
    void isSafeValue_dangerous_false() {
        assertThat(protector.isSafeValue("1' UNION SELECT 1")).isFalse();
    }

    @Test
    void isSafeValue_tooLong_false() {
        assertThat(protector.isSafeValue("x".repeat(1500))).isFalse();
    }
}
