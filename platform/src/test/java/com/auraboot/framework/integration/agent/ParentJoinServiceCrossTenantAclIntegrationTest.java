package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.crosstenant.CrossTenantAclService;
import com.auraboot.framework.agent.crosstenant.CrossTenantGrantType;
import com.auraboot.framework.agent.memory.SessionEndedEvent;
import com.auraboot.framework.agent.service.ChildRunCompletedEvent;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C.2 — verifies {@link com.auraboot.framework.agent.service.ParentJoinService#onSessionEnded}
 * gates cross-tenant ChildRunCompletedEvent delivery via the ACL.
 *
 * <p>Cases:
 * <ul>
 *   <li>A — cross-tenant + grant present: child terminal triggers
 *           ChildRunCompletedEvent normally (event observed by listener).</li>
 *   <li>B — cross-tenant + no grant: SessionEndedEvent silently dropped,
 *           ChildRunCompletedEvent is NOT published (assert listener saw zero
 *           events for the dropped pair).</li>
 * </ul>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("ParentJoinService cross-tenant ACL bridge (C.2)")
class ParentJoinServiceCrossTenantAclIntegrationTest extends BaseIntegrationTest {

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private CrossTenantAclService aclService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RecordingChildListener recorder;

    private Long parentTenant;
    private Long childTenant;

    @BeforeEach
    void setup() {
        long base = 9_790_000L + System.nanoTime() % 100_000;
        parentTenant = base;
        childTenant = base + 1L;
        MetaContext.setContext(parentTenant, testUser.getId(),
                testUser.getPid(), testUser.getUserName());
        recorder.received.clear();
    }

    @AfterEach
    void cleanup() {
        for (Long t : List.of(parentTenant, childTenant)) {
            jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM ab_agent_task WHERE tenant_id = ?", t);
        }
        jdbc.update("DELETE FROM ab_cross_tenant_grant WHERE parent_tenant_id = ?", parentTenant);
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);
        MetaContext.clear();
    }

    /** Returns (parentRunPid, childRunPid). Child is already in success status. */
    private String[] seedParentChild() {
        String parentTaskPid = UniqueIdGenerator.generate();
        String parentRunPid = UniqueIdGenerator.generate();
        String childTaskPid = UniqueIdGenerator.generate();
        String childRunPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, title, task_status, "
                        + " assignee_type, assignee_id, created_at, updated_at, created_by) "
                        + "VALUES (?, ?, 'parent', 'in_progress', 'agent', 'aurabot', NOW(), NOW(), ?)",
                parentTaskPid, parentTenant, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, "
                        + " started_at, created_at, updated_at, created_by) "
                        + "VALUES (?, ?, ?, 'aurabot', 'running', NOW(), NOW(), NOW(), ?)",
                parentRunPid, parentTenant, parentTaskPid, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, parent_id, title, task_status, "
                        + " assignee_type, assignee_id, created_at, updated_at, created_by) "
                        + "VALUES (?, ?, ?, 'child', 'in_progress', 'agent', 'aurabot', NOW(), NOW(), ?)",
                childTaskPid, childTenant, parentTaskPid, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, "
                        + " parent_run_id, subtask_origin, "
                        + " input_tokens, output_tokens, total_cost, "
                        + " started_at, created_at, updated_at, created_by) "
                        + "VALUES (?, ?, ?, 'aurabot', 'success', ?, 'delegate_task', "
                        + "        0, 0, 0, NOW(), NOW(), NOW(), ?)",
                childRunPid, childTenant, childTaskPid, parentRunPid, testUser.getId());
        return new String[] {parentRunPid, childRunPid};
    }

    @Test
    @DisplayName("A: cross-tenant + grant → ChildRunCompletedEvent emitted")
    void caseA_cross_tenant_with_grant_emits_event() {
        jdbc.update("INSERT INTO ab_cross_tenant_grant "
                        + "(parent_tenant_id, child_tenant_id, grant_type, granted_by, granted_at) "
                        + "VALUES (?, ?, ?, ?, now())",
                parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT, testUser.getId());
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);

        String[] pair = seedParentChild();
        String parentRunPid = pair[0];
        String childRunPid = pair[1];

        // Publish SessionEndedEvent — bridge should re-emit ChildRunCompletedEvent.
        eventPublisher.publishEvent(new SessionEndedEvent(
                childTenant, childRunPid, "aurabot",
                String.valueOf(testUser.getId()),
                SessionEndedEvent.TerminalOutcome.SUCCEEDED));

        assertThat(recorder.received).hasSize(1);
        assertThat(recorder.received.get(0).getParentRunId()).isEqualTo(parentRunPid);
        assertThat(recorder.received.get(0).getChildRunId()).isEqualTo(childRunPid);
        assertThat(recorder.received.get(0).getOutcome()).isEqualTo("succeeded");
    }

    @Test
    @DisplayName("B: cross-tenant + no grant → ChildRunCompletedEvent silently dropped")
    void caseB_cross_tenant_no_grant_drops_event() {
        // No grant row inserted.
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);

        String[] pair = seedParentChild();
        String childRunPid = pair[1];

        eventPublisher.publishEvent(new SessionEndedEvent(
                childTenant, childRunPid, "aurabot",
                String.valueOf(testUser.getId()),
                SessionEndedEvent.TerminalOutcome.SUCCEEDED));

        // Recorder must NOT have received an event for this dropped pair.
        boolean leaked = recorder.received.stream()
                .anyMatch(e -> e.getChildRunId().equals(childRunPid));
        assertThat(leaked)
                .as("denied cross-tenant ChildRunCompletedEvent must be silently dropped")
                .isFalse();
    }

    /**
     * Test-only listener that records every {@link ChildRunCompletedEvent}
     * Spring publishes. Concurrent-safe so flake-free under parallel cases.
     */
    @Component
    static class RecordingChildListener {
        final List<ChildRunCompletedEvent> received = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(ChildRunCompletedEvent event) {
            received.add(event);
        }
    }
}
