package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.SecureSqlRewriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReferencedTablesReproTest {
    private final SecureSqlRewriter rewriter = new SecureSqlRewriter();

    private static final String STAT =
        "SELECT count(*) AS total_orders, count(CASE WHEN e2et_order_urgent THEN 1 END) AS urgent_orders,"
        + " count(CASE WHEN e2et_order_status = 'completed' THEN 1 END) AS completed_orders FROM mt_e2et_order";
    private static final String DETAIL =
        "SELECT e2et_order_title AS title, e2et_order_type AS type, e2et_order_status AS status,"
        + " created_at::date AS order_date FROM mt_e2et_order"
        + " WHERE e2et_order_title LIKE 'HiFi订单-hifi_x%' ORDER BY id";

    @Test
    void rawStatAndDetailParseViaReferencedTables() {
        assertThat(rewriter.referencedTables(STAT)).containsExactly("mt_e2et_order");
        assertThat(rewriter.referencedTables(DETAIL)).containsExactly("mt_e2et_order");
    }

    @Test
    void wrappedDetailParsesViaReferencedTables() {
        String wrapped = "SELECT title, type, status, order_date FROM (" + DETAIL + ") AS _nq";
        assertThat(rewriter.referencedTables(wrapped)).containsExactly("mt_e2et_order");
    }

    @Test
    void setReturningFunctionsInProjectionAddNoRelationsAndDoNotThrow() {
        // Regression for the fresh-seed red cluster 2026-09-20: the quote price-evidence
        // named query expands ladder arrays via jsonb_array_elements_text inside scalar
        // subqueries of the projection. Table functions derive from already-resolved row
        // columns — they add no relation provenance and must not be rejected.
        String evidence = "SELECT e.pid AS pid,"
            + " (SELECT string_agg(n.val || '+', ' ' ORDER BY n.ord) FROM jsonb_array_elements_text(e.qo_pe_snapshot -> 'ladderNums')"
            + " WITH ORDINALITY AS n(val, ord) LEFT JOIN jsonb_array_elements_text(e.qo_pe_snapshot -> 'ladderPrices')"
            + " WITH ORDINALITY AS p(val, ord) ON n.ord = p.ord) AS price_ladder"
            + " FROM mt_qo_price_evidence_common e";
        assertThat(rewriter.referencedTables(evidence)).containsExactly("mt_qo_price_evidence_common");
    }

    @Test
    void unnestOverCteColumnsAddsNoRelationsAndDoesNotThrow() {
        String workbench = "WITH facts AS (SELECT unnest(q.qo_ql_process_points) AS point FROM mt_qo_quote_line_common q)"
            + " SELECT f.point FROM facts f";
        assertThat(rewriter.referencedTables(workbench)).containsExactly("mt_qo_quote_line_common");
    }
}
