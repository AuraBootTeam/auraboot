package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.SecureSqlRewriter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the quote BOM price waterfall (2026-09-20). The production
 * fromSql lives verbatim in a test resource because its size and CASE density
 * are what break the parser: jsqlparser 5.2 backtracks exponentially on it and
 * exceeds the 8s parse timeout, so every execution of the named query failed
 * with "Cannot establish named query source tables" before reaching the
 * database. jsqlparser 5.4 (forced in platform/build.gradle) parses it in
 * ~130ms. The assertion is falsifiable in both directions: it fails if a
 * dependency change brings back a parser that cannot handle this input, and it
 * fails if the rewriter ever loses one of the physical sources the field
 * protection must scope.
 */
class ReferencedTablesWaterfallRegressionTest {
    private final SecureSqlRewriter rewriter = new SecureSqlRewriter();

    private static String waterfallFromSql() {
        try (var in = ReferencedTablesWaterfallRegressionTest.class
                .getResourceAsStream("/meta/named-query/qo-quote-bom-price-waterfall.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException couldNotRead) {
            throw new IllegalStateException("Waterfall regression SQL resource is missing", couldNotRead);
        }
    }

    @Test
    void productionWaterfallFromSqlParsesWithinTimeoutAndYieldsAllSources() {
        String fromSql = waterfallFromSql();
        long start = System.nanoTime();
        Set<String> tables = rewriter.referencedTables(fromSql);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(millis).as("parse must stay far below the 8s parser timeout").isLessThan(4_000);
        assertThat(tables).containsExactlyInAnyOrder(
                "mt_qo_quote_line_common",
                "mt_qo_quote_common",
                "mt_qo_quote_line_price_decision_common",
                "mt_qo_price_evidence_common",
                "mt_qo_supplier_request_line_common",
                "mt_qo_offline_material_price_common");
    }

    @Test
    void resolvedProjectionWrapperFormAlsoParses() {
        // NamedQuerySourceModels.resolvePlan wraps the fromSql as
        // SELECT <projection> FROM (<fromSql>) _nq before extraction; the wrapped
        // shape must not re-introduce the backtracking blow-up.
        String wrapped = "SELECT * FROM (" + waterfallFromSql() + ") _nq";
        Set<String> tables;
        try {
            tables = rewriter.referencedTables(wrapped);
        } catch (MetaServiceException regressed) {
            throw new AssertionError(
                    "referencedTables rejected the production waterfall SQL: " + regressed.getMessage(), regressed);
        }
        assertThat(tables).contains("mt_qo_quote_line_common");
    }
}
