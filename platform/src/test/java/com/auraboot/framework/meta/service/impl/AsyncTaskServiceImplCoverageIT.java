package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.AsyncTaskDTO;
import com.auraboot.framework.meta.dto.AsyncTaskSubmitRequest;
import com.auraboot.framework.meta.entity.AsyncTask;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.AsyncTaskMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link AsyncTaskServiceImpl} — read/cancel/delete lifecycle over a
 * directly-seeded task (getTask, listTasks with/without filters, cancelTask + its
 * not-cancellable guard, deleteTask) + submitTask executor-validation. Dedicated synthetic tenant.
 * (executeTaskAsync, which runs a registered executor on a pool thread, is out of scope.)
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AsyncTaskServiceImpl Coverage IT — task read/cancel/delete lifecycle")
class AsyncTaskServiceImplCoverageIT {

    private static final long TENANT_ID = 991_000_001L;
    private static final long USER_ID = 991_000_002L;
    private static final long OTHER_USER_ID = 991_000_003L;
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private AsyncTaskServiceImpl asyncTaskService;
    @Autowired
    private AsyncTaskMapper asyncTaskMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 991_000_002L, "async-test-pid", "async-test-user");
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_async_task WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private AsyncTask seedTask(String taskType) {
        return seedTaskForUser(taskType, USER_ID);
    }

    private AsyncTask seedTaskForUser(String taskType, long createdBy) {
        AsyncTask t = new AsyncTask();
        t.setTenantId(TENANT_ID);
        t.setTaskCode(UniqueIdGenerator.generate());
        t.setTaskType(taskType);
        t.setTaskName("seed " + taskType);
        t.setStatus(AsyncTask.STATUS_PENDING);
        t.setPriority(5);
        t.setProgress(0);
        t.setRetryCount(0);
        t.setMaxRetries(3);
        t.setCreatedBy(createdBy);
        t.setCreatedAt(Instant.now());
        asyncTaskMapper.insert(t);
        return t;
    }

    @Test
    @DisplayName("listTasks is scoped to the caller — a tenant member cannot enumerate another member's tasks")
    void listTasksScopedToCaller() {
        AsyncTask mine = seedTaskForUser("scope_mine_" + seq.incrementAndGet(), USER_ID);
        AsyncTask others = seedTaskForUser("scope_other_" + seq.incrementAndGet(), OTHER_USER_ID);

        IPage<AsyncTaskDTO> asMe = asyncTaskService.listTasks(TENANT_ID, USER_ID, null, null, 1, 50);

        assertTrue(asMe.getRecords().stream().anyMatch(r -> r.getTaskCode().equals(mine.getTaskCode())),
                "caller should see their own task");
        assertTrue(asMe.getRecords().stream().noneMatch(r -> r.getTaskCode().equals(others.getTaskCode())),
                "caller must NOT see another member's task (cross-user enumeration)");
    }

    @Test
    @DisplayName("getTask + listTasks (all + filtered) + cancelTask + not-cancellable guard + deleteTask")
    void lifecycle() {
        AsyncTask seeded = seedTask("export_" + seq.incrementAndGet());

        AsyncTaskDTO dto = asyncTaskService.getTask(seeded.getTaskCode());
        assertEquals(seeded.getTaskCode(), dto.getTaskCode());

        IPage<AsyncTaskDTO> all = asyncTaskService.listTasks(TENANT_ID, USER_ID, null, null, 1, 20);
        assertTrue(all.getRecords().stream().anyMatch(r -> r.getTaskCode().equals(seeded.getTaskCode())));

        IPage<AsyncTaskDTO> filtered = asyncTaskService.listTasks(
                TENANT_ID, USER_ID, AsyncTask.STATUS_PENDING, seeded.getTaskType(), 1, 20);
        assertTrue(filtered.getRecords().stream().anyMatch(r -> r.getTaskCode().equals(seeded.getTaskCode())));

        AsyncTaskDTO cancelled = asyncTaskService.cancelTask(seeded.getTaskCode());
        assertEquals(AsyncTask.STATUS_CANCELLED, cancelled.getStatus());
        // already cancelled -> not cancellable
        assertThrows(MetaServiceException.class, () -> asyncTaskService.cancelTask(seeded.getTaskCode()));

        asyncTaskService.deleteTask(seeded.getTaskCode());
        assertThrows(MetaServiceException.class, () -> asyncTaskService.getTask(seeded.getTaskCode()));
    }

    @Test
    @DisplayName("getTask throws for an unknown code; submitTask rejects an unregistered task type")
    void notFoundAndUnregistered() {
        assertThrows(MetaServiceException.class, () -> asyncTaskService.getTask("no_such_task_code"));

        AsyncTaskSubmitRequest req = new AsyncTaskSubmitRequest();
        req.setTaskType("no_such_executor_type");
        req.setTaskName("x");
        assertThrows(MetaServiceException.class,
                () -> asyncTaskService.submitTask(req, TENANT_ID, 991_000_002L));
    }
}
