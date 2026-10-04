package com.auraboot.framework.connector.jdbc.service;

import com.auraboot.framework.test.support.MySqlTestContainer;
import org.junit.jupiter.api.Test;

import java.sql.Driver;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the JDBC readiness precondition without requiring a local Docker daemon. */
class JdbcDriverRuntimeTest {

    @Test
    void mysqlDriverIsDiscoverableAndAcceptsTheContainerJdbcUrl() throws Exception {
        String driverClass = new MySqlTestContainer("mysql:8.0.39").getDriverClassName();
        Class.forName(driverClass);
        String url = "jdbc:mysql://127.0.0.1:3306/test?permitMysqlScheme=true";
        Driver driver = DriverManager.getDriver(url);

        assertThat(driver.getClass().getName()).isEqualTo(driverClass);
        assertThat(driver.acceptsURL(url)).isTrue();
    }
}
