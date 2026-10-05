package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.NamedQueryField;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL predicate test in the explicitly selected task namespace, no host application mocks. */
class NamedQueryKeywordPredicateIntegrationTest {
    @Test void literalSearchPreservesTenantPredicateAndRejectsSqlInjection() throws Exception {
        String url=System.getenv("TEST_DATABASE_URL"), expected=System.getenv("TEST_EXPECTED_DATABASE");
        assertThat(expected).isNotBlank().matches("[a-z][a-z0-9_]*");
        assertThat(url).startsWith("jdbc:postgresql://");
        assertThat(java.net.URI.create(url.substring(5)).getPath()).isEqualTo("/"+expected);
        try (Connection connection=DriverManager.getConnection(url,System.getenv("TEST_DATABASE_USERNAME"),System.getenv("TEST_DATABASE_PASSWORD"))) {
            try (var statement=connection.createStatement(); var result=statement.executeQuery("SELECT current_database()")) {
                assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo(expected);
            }
            try (var statement=connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE nq_keyword_regression(tenant_id BIGINT, business_code TEXT)");
            }
            for (var row : List.of(Map.entry(1L,"LOT_A%\\B"),Map.entry(2L,"LOT_A%\\B"),Map.entry(1L,"LOT_XYZB"))) {
                try (var insert=connection.prepareStatement("INSERT INTO nq_keyword_regression VALUES (?,?)")) {
                    insert.setLong(1,row.getKey());insert.setString(2,row.getValue());insert.executeUpdate();
                }
            }
            assertThat(search(connection,"LOT_A%\\B")).containsExactly("LOT_A%\\B");
            assertThat(search(connection,"lot_a%\\b")).containsExactly("LOT_A%\\B");
            assertThat(search(connection,"' OR 1=1 --")).isEmpty();
            assertThat(search(connection,"not-present")).isEmpty();
        }
    }
    private List<String> search(Connection connection,String keyword) throws Exception {
        var field=new NamedQueryField();field.setFieldCode("business_code");field.setColumnExpr("business_code");field.setSearchable(true);
        var clauses=new ArrayList<String>();var params=new HashMap<String,Object>();
        NamedQueryKeywordFilter.append(keyword,List.of(field),clauses,params);
        String sql="SELECT business_code FROM nq_keyword_regression WHERE tenant_id = ? AND "+clauses.getFirst();
        sql=sql.replace("#{params.__aura_nq_keyword}","?");
        try (var statement=connection.prepareStatement(sql)) {
            statement.setLong(1,1L);statement.setString(2,(String)params.get("__aura_nq_keyword"));
            try (var result=statement.executeQuery()) {
                List<String> values=new ArrayList<>();while(result.next())values.add(result.getString(1));return values;
            }
        }
    }
}
