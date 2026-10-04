package com.auraboot.framework.test.support;

import org.testcontainers.containers.MySQLContainer;

/** Runs the existing MySQL server fixtures with the test-only MariaDB JDBC driver. */
public final class MySqlTestContainer extends MySQLContainer<MySqlTestContainer> {

    public MySqlTestContainer(String image) {
        super(image);
        withUrlParam("permitMysqlScheme", "true");
    }

    @Override
    public String getDriverClassName() {
        return "org.mariadb.jdbc.Driver";
    }
}
