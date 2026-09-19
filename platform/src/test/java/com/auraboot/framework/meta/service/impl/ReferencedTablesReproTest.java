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
}
