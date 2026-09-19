package com.auraboot.framework.meta.service.impl;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FromSqlParseReproTest {
    private static final String[] SQLS = {
        "SELECT count(*) AS total_orders, count(CASE WHEN e2et_order_urgent THEN 1 END) AS urgent_orders,"
            + " count(CASE WHEN e2et_order_status = 'completed' THEN 1 END) AS completed_orders FROM mt_e2et_order",
        "SELECT e2et_order_title AS title, e2et_order_type AS type, e2et_order_status AS status,"
            + " created_at::date AS order_date FROM mt_e2et_order"
            + " WHERE e2et_order_title LIKE 'HiFi订单-hifi_x%' ORDER BY id",
        "SELECT e2et_order_type AS order_type, count(*) AS cnt FROM mt_e2et_order"
            + " WHERE e2et_order_title LIKE 'HiFi订单-hifi_x%' GROUP BY e2et_order_type ORDER BY cnt DESC",
        "SELECT e2et_order_status AS status, e2et_order_type AS type, count(*) AS cnt FROM mt_e2et_order"
            + " WHERE e2et_order_title LIKE 'HiFi订单-hifi_x%' GROUP BY e2et_order_status, e2et_order_type",
        "SELECT e2et_order_status AS status, count(*) AS cnt FROM mt_e2et_order"
            + " WHERE e2et_order_title LIKE 'HiFi订单-hifi_x%' GROUP BY e2et_order_status"
    };

    @Test
    void allHifiFromSqlParseAsStatements() {
        for (String sql : SQLS) {
            assertThatCodeParses(sql);
        }
    }

    private void assertThatCodeParses(String sql) {
        try {
            assertThat(CCJSqlParserUtil.parse(sql)).isNotNull();
        } catch (Exception e) {
            throw new AssertionError("cannot parse: " + sql + " -> " + e.getMessage(), e);
        }
    }
}
