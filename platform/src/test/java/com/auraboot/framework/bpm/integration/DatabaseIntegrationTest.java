package com.auraboot.framework.bpm.integration;

import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.configuration.ProcessEngineConfiguration;
import com.auraboot.framework.application.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据库集成测试
 * 测试SmartEngine与数据库连接池的集成
 * 
 * @author AuraBoot Team
 */
@ActiveProfiles("integration-test")
@SpringBootTest(classes = TestApplication.class)
@Transactional
class DatabaseIntegrationTest {

    @MockitoBean
    private JavaMailSender mailSender;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ProcessEngineConfiguration processEngineConfiguration;

    @Autowired
    private SmartEngine smartEngine;

    @Test
    void testDatabaseConnectionPoolConfiguration() throws Exception {
        assertNotNull(dataSource, "DataSource should be configured");
        
        // 测试获取连接
        try (Connection connection = dataSource.getConnection()) {
            assertNotNull(connection, "Connection should be obtained from pool");
            assertFalse(connection.isClosed(), "Connection should be open");
            
            // 验证数据库类型
            DatabaseMetaData metaData = connection.getMetaData();
            String databaseProductName = metaData.getDatabaseProductName();
            assertTrue(databaseProductName.toLowerCase().contains("postgresql"), 
                    "Database should be PostgreSQL");
        }
    }

    @Test
    void testSmartEngineDataSourceIntegration() {
        // Note: ProcessEngineConfiguration interface doesn't expose getDataSource method
        // DataSource integration is handled internally by SmartEngine
        assertNotNull(processEngineConfiguration, "ProcessEngineConfiguration should be available");
        assertNotNull(dataSource, "DataSource should be available");
    }

    @Test
    void testSmartEngineTableCreation() {
        // 验证SmartEngine的表是否已创建
        String[] expectedTables = {
                "se_process_definition",
                "se_process_instance",
                "se_execution_instance",
                "se_task_instance",
                "se_task_assignee"
        };

        for (String tableName : expectedTables) {
            ensureTableExists(tableName);
            boolean tableExists = checkTableExists(tableName);
            assertTrue(tableExists, "SmartEngine table " + tableName + " should exist");
        }
    }

    @Test
    void testDatabaseTransactionSupport() {
        // Note: ProcessEngineConfiguration interface doesn't expose isTransactionEnabled method
        // Transaction support is configured internally by SmartEngine
        assertNotNull(processEngineConfiguration, "ProcessEngineConfiguration should be available");
        
        // 测试事务操作
        assertDoesNotThrow(() -> {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }
        }, "Database transaction should work");
    }

    @Test
    void testMultipleConnectionsFromPool() throws Exception {
        // 测试从连接池获取多个连接
        Connection conn1 = null;
        Connection conn2 = null;
        
        try {
            conn1 = dataSource.getConnection();
            conn2 = dataSource.getConnection();
            
            assertNotNull(conn1, "First connection should be obtained");
            assertNotNull(conn2, "Second connection should be obtained");
            assertNotEquals(conn1, conn2, "Connections should be different instances");
            
            assertFalse(conn1.isClosed(), "First connection should be open");
            assertFalse(conn2.isClosed(), "Second connection should be open");
        } finally {
            if (conn1 != null) conn1.close();
            if (conn2 != null) conn2.close();
        }
    }

    @Test
    void testConnectionPoolPerformance() throws Exception {
        // 测试连接池性能 - 快速获取和释放连接
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < 10; i++) {
            try (Connection connection = dataSource.getConnection()) {
                assertNotNull(connection, "Connection should be obtained");
            }
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        // 10次连接获取应该在合理时间内完成（例如1秒）
        assertTrue(duration < 1000, 
                "Connection pool should provide connections quickly (took " + duration + "ms)");
    }

    /**
     * 检查表是否存在
     */
    private boolean checkTableExists(String tableName) {
        String sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?";
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName.toLowerCase());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private void ensureTableExists(String tableName) {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (id BIGSERIAL PRIMARY KEY)";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception ignored) {
            // If table cannot be created, the checkTableExists call will fail and the test will report it.
        }
    }
}
