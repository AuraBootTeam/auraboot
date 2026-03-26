package com.auraboot.framework.application.bootstrap.seeder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueryOperatorSeeder {
    private final JdbcTemplate jdbcTemplate;

    public void seed() {
        String sql = """
            INSERT INTO ab_query_operator (op_code, sql_tpl, value_type, notes)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (op_code) DO NOTHING
            """;

        Object[][] ops = {
            {"eq", "{column} = {value}", "any", "Equals"},
            {"ne", "{column} != {value}", "any", "Not equals"},
            {"gt", "{column} > {value}", "number", "Greater than"},
            {"gte", "{column} >= {value}", "number", "Greater or equal"},
            {"lt", "{column} < {value}", "number", "Less than"},
            {"lte", "{column} <= {value}", "number", "Less or equal"},
            {"like", "{column} LIKE {value}", "string", "Like"},
            {"ilike", "{column} ILIKE {value}", "string", "Case-insensitive like"},
            {"in", "{column} IN ({value})", "array", "In"},
            {"not_in", "{column} NOT IN ({value})", "array", "Not in"},
            {"is_null", "{column} IS NULL", "none", "Is null"},
            {"is_not_null", "{column} IS NOT NULL", "none", "Is not null"},
            {"between", "{column} BETWEEN {value1} AND {value2}", "range", "Between"},
            {"starts_with", "{column} LIKE {value}||'%'", "string", "Starts with"},
            {"ends_with", "{column} LIKE '%'||{value}", "string", "Ends with"},
            {"contains", "{column} LIKE '%'||{value}||'%'", "string", "Contains"},
        };

        int count = 0;
        for (Object[] op : ops) {
            count += jdbcTemplate.update(sql, op[0], op[1], op[2], op[3]);
        }
        log.info("QueryOperatorSeeder: seeded {} operators (skipped {} existing)", count, ops.length - count);
    }
}
