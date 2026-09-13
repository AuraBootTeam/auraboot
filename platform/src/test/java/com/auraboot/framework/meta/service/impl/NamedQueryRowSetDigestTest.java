package com.auraboot.framework.meta.service.impl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NamedQueryRowSetDigestTest {

    private Map<String, Object> row(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put("record_key", key);
        map.put("amount", value);
        return map;
    }

    @Test void identicalRowSetsDigestEquallyRegardlessOfOrder() {
        List<Map<String, Object>> first = List.of(row("a", 1L), row("b", 2L));
        List<Map<String, Object>> second = List.of(row("b", 2L), row("a", 1L));
        assertThat(NamedQueryRowSetDigest.digest(first, List.of("record_key", "amount")))
                .isEqualTo(NamedQueryRowSetDigest.digest(second, List.of("record_key", "amount")));
    }

    @Test void differingRowSetsDigestDifferently() {
        List<Map<String, Object>> first = List.of(row("a", 1L), row("b", 2L));
        List<Map<String, Object>> missing = List.of(row("a", 1L));
        List<Map<String, Object>> changed = List.of(row("a", 1L), row("b", 3L));
        String base = NamedQueryRowSetDigest.digest(first, List.of("record_key", "amount"));
        assertThat(base).isNotEqualTo(NamedQueryRowSetDigest.digest(missing, List.of("record_key", "amount")));
        assertThat(base).isNotEqualTo(NamedQueryRowSetDigest.digest(changed, List.of("record_key", "amount")));
    }

    @Test void nullAndEmptyAndNumericFormsAreCanonicalized() {
        String withNull = NamedQueryRowSetDigest.digest(
                List.of(row("a", null)), List.of("record_key", "amount"));
        String withEmpty = NamedQueryRowSetDigest.digest(
                List.of(row("a", "")), List.of("record_key", "amount"));
        assertThat(withNull).isNotEqualTo(withEmpty);
        assertThat(NamedQueryRowSetDigest.digest(List.of(row("a", new BigDecimal("1.0"))), List.of("record_key", "amount")))
                .isEqualTo(NamedQueryRowSetDigest.digest(List.of(row("a", new BigDecimal("1.00"))), List.of("record_key", "amount")))
                .isEqualTo(NamedQueryRowSetDigest.digest(List.of(row("a", 1L)), List.of("record_key", "amount")));
    }

    @Test void fieldCodeOrderChangesTheDigest() {
        List<Map<String, Object>> rows = List.of(row("a", 1L));
        assertThat(NamedQueryRowSetDigest.digest(rows, List.of("record_key", "amount")))
                .isNotEqualTo(NamedQueryRowSetDigest.digest(rows, List.of("amount", "record_key")));
    }
}
