package com.auraboot.framework.meta.ddl;

import com.auraboot.framework.meta.entity.AsyncTask;
import com.auraboot.framework.meta.mapper.AsyncTaskMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real mapper SQL, migration precondition, and database fencing in a test-owned schema. */
class AsyncTaskLeasePostgresIT {
    @Test void leaseOwnershipFencesRecoveryCancellationAndStaleResults() throws Exception {
        assertEquals("crm-release-foundation", required("AURA_RUNTIME_NAME"));
        String database = required("POSTGRES_DB");
        assertEquals("auracrm_" + required("AURA_WORKSPACE_SLOT"), database);
        String url = "jdbc:postgresql://" + required("POSTGRES_HOST") + ":" + required("POSTGRES_PORT") + "/" + database;
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, required("DATABASE_USERNAME"), required("DATABASE_PASSWORD")));
        String schema = "async_lease_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var source = new DriverManagerDataSource(url + "?currentSchema=" + schema,
                    required("DATABASE_USERNAME"), required("DATABASE_PASSWORD"));
            var jdbc = new JdbcTemplate(source);
            String baseline = resource("V20260618000000__baseline_core_schema.sql");
            int start = baseline.indexOf("CREATE TABLE IF NOT EXISTS ab_async_task (");
            assertTrue(start >= 0);
            jdbc.execute(baseline.substring(start, baseline.indexOf(";", start) + 1));
            String migration = resource("V20260925020000__async_task_execution_leases.sql");
            jdbc.update("INSERT INTO ab_async_task(tenant_id,task_code,task_type,task_name,created_by,status) VALUES (1,'legacy','custom','legacy',1,'running')");
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> jdbc.execute(migration)));
            assertEquals("running", jdbc.queryForObject("SELECT status FROM ab_async_task WHERE task_code='legacy'", String.class));
            jdbc.update("DELETE FROM ab_async_task WHERE task_code='legacy'");
            transaction.executeWithoutResult(status -> jdbc.execute(migration));
            Long id = jdbc.queryForObject("""
                    INSERT INTO ab_async_task(tenant_id,task_code,task_type,task_name,created_by,input_params)
                    VALUES (1,'owned','command-handler','fixture',1,'{"resumeOnRestart":true}'::jsonb) RETURNING id
                    """, Long.class);
            var configuration = new Configuration(new Environment("fixture", new JdbcTransactionFactory(), source));
            configuration.addMapper(AsyncTaskMapper.class);
            var factory = new SqlSessionFactoryBuilder().build(configuration);
            try (var first = factory.openSession(true); var second = factory.openSession(true)) {
                var owner = first.getMapper(AsyncTaskMapper.class);
                var other = second.getMapper(AsyncTaskMapper.class);
                assertEquals(0, other.claimPending(id, 2L, "wrong-tenant"));
                assertEquals(1, owner.claimPending(id, 1L, "first"));
                assertEquals(0, other.claimPending(id, 1L, "second"));
                assertEquals(0, other.renewLease(id, 1L, "second"));
                assertEquals(1, owner.renewLease(id, 1L, "first"));
                assertEquals(0, other.requeueExpiredResumableTasks());
                assertEquals(0, other.failExpiredTasks());
                assertEquals(0, other.updateOwnedProgress(id, 1L, "second", 20, "wrong owner"));
                assertEquals(1, owner.updateOwnedProgress(id, 1L, "first", 30, "owned"));
                // Advancing this fixture's lease simulates a dead worker without wall-clock sleeps.
                jdbc.update("UPDATE ab_async_task SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", id);
                assertEquals(0, owner.renewLease(id, 1L, "first"));
                assertEquals(1, other.requeueExpiredResumableTasks());
                assertEquals(1, other.claimPending(id, 1L, "second"));
                AsyncTask result = new AsyncTask();
                result.setId(id); result.setTenantId(1L); result.setExecutionToken("first");
                result.setStatus("completed"); result.setProgress(100); result.setRetryCount(0);
                assertEquals(0, owner.finishOwned(result));
                assertEquals(0, other.cancelActive(id, 2L));
                assertEquals(1, other.cancelActive(id, 1L));
                result.setExecutionToken("second");
                assertEquals(0, other.finishOwned(result));
                assertEquals("cancelled", jdbc.queryForObject("SELECT status FROM ab_async_task WHERE id=?", String.class, id));
                Long success = jdbc.queryForObject("""
                        INSERT INTO ab_async_task(tenant_id,task_code,task_type,task_name,created_by)
                        VALUES (1,'success','custom','fixture',1) RETURNING id
                        """, Long.class);
                assertEquals(1, owner.claimPending(success, 1L, "success-token"));
                result.setId(success); result.setExecutionToken("success-token");
                assertEquals(1, owner.finishOwned(result));
                assertEquals("completed", jdbc.queryForObject("SELECT status FROM ab_async_task WHERE id=?", String.class, success));
                Long expired = jdbc.queryForObject("""
                        INSERT INTO ab_async_task(tenant_id,task_code,task_type,task_name,created_by)
                        VALUES (1,'expired','custom','fixture',1) RETURNING id
                        """, Long.class);
                assertEquals(1, owner.claimPending(expired, 1L, "expired-token"));
                jdbc.update("UPDATE ab_async_task SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", expired);
                assertEquals(0, owner.requeueExpiredResumableTasks());
                assertEquals(1, owner.failExpiredTasks());
                assertEquals("failed", jdbc.queryForObject("SELECT status FROM ab_async_task WHERE id=?", String.class, expired));
            }
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
    private static String resource(String name) throws Exception {
        try (var input = AsyncTaskLeasePostgresIT.class.getResourceAsStream("/db/migration/core/" + name)) {
            assertNotNull(input); return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static String required(String name) {
        String value = System.getenv(name); assertNotNull(value, name); assertFalse(value.isBlank(), name); return value;
    }
}
