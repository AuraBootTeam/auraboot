package com.auraboot.framework.connector.jdbc;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcConnectorSchemaTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Test
    void migrationCreatesTablesAndColumns() throws Exception {
        String sql = Files.readString(Path.of(
            "src/main/resources/database/migrations/2026-05-09-jdbc-connector.sql"));
        try (Connection c = DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);

            // Assert ab_jdbc_connector exists with 14 columns (id + 13 user cols)
            try (ResultSet rs = c.getMetaData().getColumns(null, "public", "ab_jdbc_connector", null)) {
                int cols = 0;
                while (rs.next()) cols++;
                assertThat(cols).isEqualTo(14);
            }
            // Assert ab_jdbc_connector_endpoint exists with 6 columns
            try (ResultSet rs = c.getMetaData().getColumns(null, "public", "ab_jdbc_connector_endpoint", null)) {
                int cols = 0;
                while (rs.next()) cols++;
                assertThat(cols).isEqualTo(6);
            }
        }
    }
}
