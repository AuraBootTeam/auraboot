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
        String containerDriverClass = new MySqlTestContainer("mysql:8.0.39").getDriverClassName();
        Class.forName(containerDriverClass);
        String url = "jdbc:mysql://127.0.0.1:3306/test?permitMysqlScheme=true";
        Driver driver = DriverManager.getDriver(url);

        // The runtime ships both the MariaDB and the MySQL drivers, so DriverManager
        // may resolve either for jdbc:mysql: URLs; the readiness precondition is a
        // discoverable driver that actually accepts the connector URL, not a
        // specific vendor.
        assertThat(driver.getClass().getName()).isIn(containerDriverClass, "com.mysql.cj.jdbc.Driver");
        assertThat(driver.acceptsURL(url)).isTrue();
    }
}
