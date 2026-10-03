package com.auraboot.framework.connector.sdk;

import com.auraboot.framework.test.support.MySqlTestContainer;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class TestContainersMysqlAvailableTest {
    @Container
    static MySQLContainer<?> mysql = new MySqlTestContainer("mysql:8.0.39");

    @Test
    void selectsConstant() throws Exception {
        try (Connection c = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }
}
