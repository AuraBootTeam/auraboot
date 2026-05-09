package com.auraboot.framework.meta.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlSafetyUtilsTest {

    // ===== Identifier validation =====

    @Test
    void validateIdentifier_valid_passes() {
        SqlSafetyUtils.validateIdentifier("user_table", "table");
        SqlSafetyUtils.validateIdentifier("_col_1", "col");
    }

    @Test
    void validateIdentifier_null_throws() {
        assertThatThrownBy(() -> SqlSafetyUtils.validateIdentifier(null, "tbl"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateIdentifier_invalid_throws() {
        assertThatThrownBy(() -> SqlSafetyUtils.validateIdentifier("1foo", "tbl"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlSafetyUtils.validateIdentifier("foo bar", "tbl"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlSafetyUtils.validateIdentifier("a;b", "tbl"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isValidIdentifier_correctClassification() {
        assertThat(SqlSafetyUtils.isValidIdentifier("good_name")).isTrue();
        assertThat(SqlSafetyUtils.isValidIdentifier(null)).isFalse();
        assertThat(SqlSafetyUtils.isValidIdentifier("bad-name")).isFalse();
    }

    // ===== SQL fragment validation =====

    @Test
    void validateSqlFragment_blankAllowed() {
        SqlSafetyUtils.validateSqlFragment(null);
        SqlSafetyUtils.validateSqlFragment("");
        SqlSafetyUtils.validateSqlFragment("   ");
    }

    @Test
    void validateSqlFragment_safeFragment_passes() {
        SqlSafetyUtils.validateSqlFragment("status = 'active'");
        SqlSafetyUtils.validateSqlFragment("col1 > 5 AND col2 < 10");
    }

    @Test
    void validateSqlFragment_semicolon_throws() {
        assertThatThrownBy(() -> SqlSafetyUtils.validateSqlFragment("a=1;DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateSqlFragment_comments_throws() {
        assertThatThrownBy(() -> SqlSafetyUtils.validateSqlFragment("a -- bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlSafetyUtils.validateSqlFragment("/* bad */"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateSqlFragment_nestedParens_throws() {
        assertThatThrownBy(() -> SqlSafetyUtils.validateSqlFragment("a IN (1)) extra"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlSafetyUtils.validateSqlFragment("(a in (b))"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateSqlFragment_unbalancedParens_throws() {
        assertThatThrownBy(() -> SqlSafetyUtils.validateSqlFragment("(a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateSqlFragment_dangerousKeyword_throws() {
        assertThatThrownBy(() -> SqlSafetyUtils.validateSqlFragment("a UNION select 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlSafetyUtils.validateSqlFragment("DROP me"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateSqlFragment_multiwordKeyword_throws() {
        assertThatThrownBy(() -> SqlSafetyUtils.validateSqlFragment("x INTO OUTFILE '/etc/x'"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void containsDangerousPatterns_blank_false() {
        assertThat(SqlSafetyUtils.containsDangerousPatterns(null)).isFalse();
        assertThat(SqlSafetyUtils.containsDangerousPatterns("")).isFalse();
    }

    @Test
    void containsDangerousPatterns_dangerous_true() {
        assertThat(SqlSafetyUtils.containsDangerousPatterns("a; b")).isTrue();
        assertThat(SqlSafetyUtils.containsDangerousPatterns("DROP x")).isTrue();
        assertThat(SqlSafetyUtils.containsDangerousPatterns("a -- comment")).isTrue();
        assertThat(SqlSafetyUtils.containsDangerousPatterns("(a in (b))")).isTrue();
        assertThat(SqlSafetyUtils.containsDangerousPatterns("LOAD_FILE('/x')")).isTrue();
    }

    @Test
    void containsDangerousPatterns_safe_false() {
        assertThat(SqlSafetyUtils.containsDangerousPatterns("a = 5")).isFalse();
    }

    // ===== Select-only validation =====

    @Test
    void validateSelectOnlySql_validSelect_passes() {
        SqlSafetyUtils.validateSelectOnlySql("SELECT * FROM t WHERE id = 1");
        SqlSafetyUtils.validateSelectOnlySql("  select id from foo  ");
    }

    @Test
    void validateSelectOnlySql_empty_throws() {
        assertThatThrownBy(() -> SqlSafetyUtils.validateSelectOnlySql(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlSafetyUtils.validateSelectOnlySql("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateSelectOnlySql_notStartingSelect_throws() {
        assertThatThrownBy(() -> SqlSafetyUtils.validateSelectOnlySql("UPDATE t SET x=1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateSelectOnlySql_semicolon_throws() {
        assertThatThrownBy(() -> SqlSafetyUtils.validateSelectOnlySql("SELECT 1; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateSelectOnlySql_comment_throws() {
        assertThatThrownBy(() -> SqlSafetyUtils.validateSelectOnlySql("SELECT 1 -- noise"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateSelectOnlySql_forbiddenKeyword_throws() {
        assertThatThrownBy(() -> SqlSafetyUtils.validateSelectOnlySql("SELECT * FROM t UNION SELECT 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlSafetyUtils.validateSelectOnlySql("SELECT * INTO OUTFILE '/x' FROM t"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== Limit clamping =====

    @Test
    void clampLimit_clampsToBounds() {
        assertThat(SqlSafetyUtils.clampLimit(0, 100)).isEqualTo(1);
        assertThat(SqlSafetyUtils.clampLimit(50, 100)).isEqualTo(50);
        assertThat(SqlSafetyUtils.clampLimit(500, 100)).isEqualTo(100);
        assertThat(SqlSafetyUtils.clampLimit(-5, 100)).isEqualTo(1);
    }
}
