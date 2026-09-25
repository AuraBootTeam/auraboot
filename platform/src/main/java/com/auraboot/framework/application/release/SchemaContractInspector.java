package com.auraboot.framework.application.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;

/** Read-only PostgreSQL catalog observation. It never repairs or publishes a schema. */
@Component
public class SchemaContractInspector {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public SchemaContractInspector(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }
    public record Column(String name, String type, boolean nullable, String defaultExpression, String identity, String generated) {}
    public record Constraint(String name, String definition) {}
    public record Contract(String schema, String table, List<Column> columns, List<Constraint> constraints) {}
    public record Observation(boolean satisfied, List<String> findings) {}

    public Observation inspect(Contract contract) {
        if (contract == null || contract.schema() == null || contract.schema().isBlank()
                || contract.table() == null || contract.table().isBlank()
                || contract.columns() == null || contract.columns().isEmpty() || contract.constraints() == null) {
            throw new IllegalArgumentException("Explicit schema, table, columns and constraint list required");
        }
        var names = new HashSet<String>();
        for (var column : contract.columns()) {
            if (column == null || column.name() == null || column.name().isBlank() || !names.add(column.name())
                    || column.type() == null || column.type().isBlank() || column.identity() == null || column.generated() == null) {
                throw new IllegalArgumentException("Invalid or duplicate column contract");
            }
        }
        names.clear();
        for (var constraint : contract.constraints()) {
            if (constraint == null || constraint.name() == null || constraint.name().isBlank() || !names.add(constraint.name())
                    || constraint.definition() == null || constraint.definition().isBlank()) {
                throw new IllegalArgumentException("Invalid or duplicate constraint contract");
            }
        }
        // A single SQL statement gives all catalog fields one MVCC snapshot.
        var snapshots = jdbc.query("""
                SELECT c.relkind,
                  COALESCE((SELECT jsonb_agg(jsonb_build_object('name',a.attname,'type',format_type(a.atttypid,a.atttypmod),
                    'nullable',NOT a.attnotnull,'defaultExpression',pg_get_expr(d.adbin,d.adrelid),
                    'identity',a.attidentity,'generated',a.attgenerated))
                    FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                    WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped),'[]'::jsonb)::text AS columns,
                  COALESCE((SELECT jsonb_agg(jsonb_build_object('name',k.conname,'definition',pg_get_constraintdef(k.oid),
                    'validated',k.convalidated,'indexUsable',CASE WHEN k.conindid=0 THEN true ELSE i.indisvalid AND i.indisready END))
                    FROM pg_constraint k LEFT JOIN pg_index i ON i.indexrelid=k.conindid
                    WHERE k.conrelid=c.oid),'[]'::jsonb)::text AS constraints
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?
                """, (row, index) -> new String[]{row.getString(1), row.getString(2), row.getString(3)}, contract.schema(), contract.table());
        if (snapshots.size() != 1) return new Observation(false, List.of("schema-table-missing"));
        var snapshot = snapshots.getFirst();
        if (!List.of("r", "p").contains(snapshot[0])) return new Observation(false, List.of("schema-relation-not-table"));
        var findings = new ArrayList<String>();
        try {
            var columns = mapper.readTree(snapshot[1]);
            var constraints = mapper.readTree(snapshot[2]);
            for (var expected : contract.columns()) {
                var actual = java.util.stream.StreamSupport.stream(columns.spliterator(), false)
                        .filter(item -> expected.name().equals(item.path("name").asText())).findFirst();
                if (actual.isEmpty()) findings.add("schema-column-missing:" + expected.name());
                else if (!expected.equals(mapper.treeToValue(actual.get(), Column.class))) findings.add("schema-column-mismatch:" + expected.name());
            }
            for (var expected : contract.constraints()) {
                var actual = java.util.stream.StreamSupport.stream(constraints.spliterator(), false)
                        .filter(item -> expected.name().equals(item.path("name").asText())).findFirst();
                if (actual.isEmpty()) findings.add("schema-constraint-missing:" + expected.name());
                else if (!expected.definition().equals(actual.get().path("definition").asText())
                        || !actual.get().path("validated").asBoolean() || !actual.get().path("indexUsable").asBoolean()) {
                    findings.add("schema-constraint-mismatch:" + expected.name());
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("PostgreSQL catalog observation could not be decoded", failure);
        }
        return new Observation(findings.isEmpty(), List.copyOf(findings));
    }
}
