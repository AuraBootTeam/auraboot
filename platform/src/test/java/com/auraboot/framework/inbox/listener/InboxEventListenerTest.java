package com.auraboot.framework.inbox.listener;

import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class InboxEventListenerTest {

    @Mock
    private InboxService inboxService;
    @Mock private com.auraboot.framework.rbac.mapper.RoleMapper roleMapper;
    @Mock private com.auraboot.framework.rbac.mapper.UserRoleMapper userRoleMapper;

    @Mock
    private UserService userService;

    @Test
    void createsReadableAssignmentTitleWithoutStateFallbackMarkers() {
        InboxEventListener listener = new InboxEventListener(inboxService, new ObjectMapper(), userService, roleMapper, userRoleMapper);
        CommandCompletedEvent event = buildStateTransitionEvent(
                "crm:activate_campaign",
                Map.of()
        );

        listener.onCommandCompleted(event);

        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxService).createItem(captor.capture());
        InboxItem item = captor.getValue();

        assertThat(item.getItemType()).isEqualTo("assignment");
        assertThat(item.getTitle()).isEqualTo("Activate Campaign");
        assertThat(item.getSubtitle()).isEqualTo("Campaign #01KTESTCAMPAIGN");
        assertThat(item.getRecordPid()).isEqualTo("01KTESTCAMPAIGN");
        assertThat(item.getDeepLink()).isEqualTo("auraboot://object/crm_campaign/01KTESTCAMPAIGN");
        assertThat(item.getCardPayload()).contains("\"commandCode\":\"crm:activate_campaign\"");
        assertThat(item.getCardPayload())
                .contains("\"sourceRecordPid\":\"01KTESTCAMPAIGN\"")
                .contains("\"recordPid\":\"01KTESTCAMPAIGN\"")
                .doesNotContain("\"recordId\"");
    }

    @Test
    void createsStateTransitionTitleWhenFromAndToStatesExist() {
        InboxEventListener listener = new InboxEventListener(inboxService, new ObjectMapper(), userService, roleMapper, userRoleMapper);
        Map<String, Object> payload = new HashMap<>();
        payload.put("fromState", "draft");
        payload.put("toState", "active");
        CommandCompletedEvent event = buildStateTransitionEvent("crm:activate_campaign", payload);

        listener.onCommandCompleted(event);

        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxService).createItem(captor.capture());
        InboxItem item = captor.getValue();

        assertThat(item.getTitle()).isEqualTo("Activate Campaign: Draft → Active");
        assertThat(item.getSubtitle()).isEqualTo("Campaign #01KTESTCAMPAIGN");
    }

    @Test
    void ignoresNonStateTransitionCommandEvents() {
        InboxEventListener listener = new InboxEventListener(inboxService, new ObjectMapper(), userService, roleMapper, userRoleMapper);
        CommandCompletedEvent event = new CommandCompletedEvent(
                100L,
                "01KTESTCAMPAIGN",
                "crm_campaign",
                Map.of(),
                "crm:update_campaign",
                "update"
        );
        event.addMetadata("actorId", 42L);

        listener.onCommandCompleted(event);

        verifyNoInteractions(inboxService);
    }

    private CommandCompletedEvent buildStateTransitionEvent(String commandCode, Map<String, Object> payload) {
        CommandCompletedEvent event = new CommandCompletedEvent(
                100L,
                "01KTESTCAMPAIGN",
                "crm_campaign",
                payload,
                commandCode,
                "state_transition"
        );
        event.addMetadata("actorId", 42L);
        event.addMetadata("actorName", "Alex");
        return event;
    }
}


    // ==================== BPM task_assigned group fan-out (F5) ====================

    private com.auraboot.framework.bpm.event.BpmEvent groupTaskAssignedEvent(long taskInstanceId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("assigneeIds", java.util.List.of("wd_manager"));
        payload.put("taskInstanceId", String.valueOf(taskInstanceId));
        payload.put("taskName", "主管审批");
        payload.put("processName", "请假审批");
        payload.put("businessKey", "wd_leave_request:01HPID");
        return com.auraboot.framework.bpm.event.BpmEvent.of(1L, "task_assigned", "bpm",
                "wd_leave_approval", String.valueOf(taskInstanceId), "task_manager_approve", payload);
    }

    @Test
    void taskAssignedWithGroupAssigneeFansOutToAllRoleMembers() {
        com.auraboot.framework.rbac.entity.Role role = new com.auraboot.framework.rbac.entity.Role();
        role.setId(9L);
        role.setCode("wd_manager");
        when(roleMapper.findByTenantIdAndCode(1L, "wd_manager")).thenReturn(role);
        when(userRoleMapper.findUserIdsByRoleIdAndTenantId(9L, 1L)).thenReturn(java.util.List.of(11L, 12L));

        InboxEventListener listener = new InboxEventListener(inboxService, new ObjectMapper(), userService, roleMapper, userRoleMapper);
        listener.onBpmEvent(groupTaskAssignedEvent(555L));

        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxService, times(2)).createItem(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(InboxItem::getUserId, InboxItem::getClientItemId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(11L, "bpm_task_555_11"),
                        org.assertj.core.groups.Tuple.tuple(12L, "bpm_task_555_12"));
        assertThat(captor.getAllValues()).allSatisfy(item -> {
            assertThat(item.getItemType()).isEqualTo("approval");
            assertThat(item.getStatus()).isEqualTo("pending");
        });
    }

    @Test
    void taskAssignedWithUnknownGroupCreatesNothing() {
        when(roleMapper.findByTenantIdAndCode(1L, "wd_manager")).thenReturn(null);

        InboxEventListener listener = new InboxEventListener(inboxService, new ObjectMapper(), userService, roleMapper, userRoleMapper);
        listener.onBpmEvent(groupTaskAssignedEvent(555L));

        verify(inboxService, never()).createItem(org.mockito.ArgumentMatchers.any());
    }

    /**
     * Concurrency regression: N tasks fanned out concurrently to the same role must
     * produce exactly one pending item per (task, member) with no lost or duplicated
     * updates, and the listener must not throw under parallel event delivery.
     */
    @Test
    void concurrentGroupFanOutCreatesExactlyOneItemPerMemberPerTask() throws Exception {
        com.auraboot.framework.rbac.entity.Role role = new com.auraboot.framework.rbac.entity.Role();
        role.setId(9L);
        role.setCode("wd_manager");
        when(roleMapper.findByTenantIdAndCode(1L, "wd_manager")).thenReturn(role);
        when(userRoleMapper.findUserIdsByRoleIdAndTenantId(9L, 1L)).thenReturn(java.util.List.of(11L, 12L));

        InboxEventListener listener = new InboxEventListener(inboxService, new ObjectMapper(), userService, roleMapper, userRoleMapper);

        int tasks = 40;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(tasks);
        java.util.List<Throwable> failures = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int t = 0; t < tasks; t++) {
            final long taskId = 1000L + t;
            pool.submit(() -> {
                try {
                    listener.onBpmEvent(groupTaskAssignedEvent(taskId));
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(failures).isEmpty();
        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxService, times(2 * tasks)).createItem(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(InboxItem::getClientItemId)
                .doesNotHaveDuplicates();
        // 每任务两个成员各一项
        assertThat(captor.getAllValues())
                .extracting(item -> item.getClientItemId().substring(item.getClientItemId().lastIndexOf('_') + 1))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("11"), org.assertj.core.groups.Tuple.tuple("12"));
    }

    // ==================== A4: claim guarantees the claimer's item ====================

    private com.auraboot.framework.bpm.event.BpmEvent taskClaimedEvent(long taskInstanceId, long claimerUserId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskInstanceId", String.valueOf(taskInstanceId));
        payload.put("claimUserId", String.valueOf(claimerUserId));
        return com.auraboot.framework.bpm.event.BpmEvent.of(1L, "task_claimed", "bpm",
                "wd_leave_approval", String.valueOf(taskInstanceId), "task_manager_approve", payload);
    }

    @Test
    void taskClaimedCreatesClaimersApprovalItemWhenMissing() {
        com.auraboot.framework.rbac.entity.Role role = new com.auraboot.framework.rbac.entity.Role();
        role.setId(9L);
        role.setCode("wd_manager");
        when(roleMapper.findByTenantIdAndCode(1L, "wd_manager")).thenReturn(role);
        when(userRoleMapper.findUserIdsByRoleIdAndTenantId(9L, 1L)).thenReturn(java.util.List.of(11L));

        InboxEventListener listener = new InboxEventListener(inboxService, new ObjectMapper(), userService, roleMapper, userRoleMapper);
        listener.onBpmEvent(taskClaimedEvent(777L, 11L));

        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxService).createItem(captor.capture());
        InboxItem item = captor.getValue();
        assertThat(item.getItemType()).isEqualTo("approval");
        assertThat(item.getUserId()).isEqualTo(11L);
        assertThat(item.getClientItemId()).isEqualTo("bpm_task_777_11");
        assertThat(item.getStatus()).isEqualTo("pending");
    }
