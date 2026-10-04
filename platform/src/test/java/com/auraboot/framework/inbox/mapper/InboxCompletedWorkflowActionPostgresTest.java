package com.auraboot.framework.inbox.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL mapper contract using connection-local fixtures, never product rows. */
class InboxCompletedWorkflowActionPostgresTest {
    private Connection connection;
    private SqlSession session;
    private InboxItemMapper mapper;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("AURA_TEST_POSTGRES_JDBC_URL");
        assertNotNull(url, "Managed PostgreSQL URL is required; this test must not skip or use H2");
        connection = DriverManager.getConnection(url, "auraboot", System.getenv("POSTGRES_PASSWORD"));
        connection.createStatement().execute("""
            CREATE TEMP TABLE ab_inbox_item (
                id BIGINT PRIMARY KEY, tenant_id BIGINT, user_id BIGINT,
                source_id TEXT, source_type TEXT, status TEXT, action_taken TEXT,
                acted_at TIMESTAMP
            )
            """);
        connection.createStatement().execute("""
            INSERT INTO ab_inbox_item VALUES
                (1, 10, 20, 'task-1', 'workflow', 'closed', NULL, TIMESTAMP '2026-10-01 10:00:00'),
                (2, 10, 20, 'task-2', 'bpm', 'pending', NULL, NULL),
                (3, 10, 20, 'task-3', 'workflow', 'closed', 'claimed_by_other', NULL),
                (4, 10, 20, 'task-4', 'external', 'closed', NULL, NULL)
            """);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(InboxItemMapper.class);
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(connection);
        mapper = session.getMapper(InboxItemMapper.class);
    }

    @AfterEach
    void close() throws Exception {
        if (session != null) session.close();
        if (connection != null) connection.close();
    }

    @Test
    void closedTaskKeepsEngineClosureAndTimestampWhileRecordingDecision() throws Exception {
        assertEquals(1, mapper.recordCompletedWorkflowAction(1L, 10L, 20L, "task-1", "approved"));
        try (ResultSet row = connection.createStatement().executeQuery("SELECT * FROM ab_inbox_item WHERE id=1")) {
            assertTrue(row.next());
            assertEquals("closed", row.getString("status"));
            assertEquals("approved", row.getString("action_taken"));
            assertEquals("2026-10-01 10:00:00.0", row.getTimestamp("acted_at").toString());
        }
    }

    @Test
    void pendingTaskRecordsRejectionAndTransitionsToActed() throws Exception {
        assertEquals(1, mapper.recordCompletedWorkflowAction(2L, 10L, 20L, "task-2", "rejected"));
        try (ResultSet row = connection.createStatement().executeQuery("SELECT * FROM ab_inbox_item WHERE id=2")) {
            assertTrue(row.next());
            assertEquals("acted", row.getString("status"));
            assertEquals("rejected", row.getString("action_taken"));
            assertNotNull(row.getTimestamp("acted_at"));
        }
    }

    @Test
    void decisionRequiresExactTenantUserTaskAndWorkflowSource() {
        assertEquals(0, mapper.recordCompletedWorkflowAction(1L, 11L, 20L, "task-1", "approved"));
        assertEquals(0, mapper.recordCompletedWorkflowAction(1L, 10L, 21L, "task-1", "approved"));
        assertEquals(0, mapper.recordCompletedWorkflowAction(1L, 10L, 20L, "task-other", "approved"));
        assertEquals(0, mapper.recordCompletedWorkflowAction(4L, 10L, 20L, "task-4", "approved"));
    }

    @Test
    void conflictingClosureReasonCannotBeOverwritten() {
        assertEquals(0, mapper.recordCompletedWorkflowAction(3L, 10L, 20L, "task-3", "approved"));
        assertEquals(1, mapper.recordCompletedWorkflowAction(1L, 10L, 20L, "task-1", "approved"));
        assertEquals(1, mapper.recordCompletedWorkflowAction(1L, 10L, 20L, "task-1", "approved"));
        assertEquals(0, mapper.recordCompletedWorkflowAction(1L, 10L, 20L, "task-1", "rejected"));
    }
}
