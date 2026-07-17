package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.instance.impl.DefaultTaskAssigneeInstance;
import com.auraboot.smart.framework.engine.instance.impl.DefaultTaskInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.smart.framework.engine.service.command.TaskCommandService;
import com.auraboot.smart.framework.engine.service.param.query.PendingTaskQueryParam;
import com.auraboot.smart.framework.engine.service.param.query.TaskInstanceQueryByAssigneeParam;
import com.auraboot.smart.framework.engine.service.query.TaskQueryService;
import com.auraboot.smart.framework.engine.service.query.VariableQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskServiceTest {

    @AfterEach
    void clearMetaContext() {
        MetaContext.clear();
    }

    @Test
    void rejectTaskWithoutCommentFailsBeforeEngineAccess() {
        SmartEngine smartEngine = mock(SmartEngine.class);
        TaskService service = new TaskService(
                smartEngine,
                mock(BpmAuditService.class),
                mock(BpmTaskActionsResolver.class),
                mock(RoleMapper.class)
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.rejectTask("task-1", " ", null)
        );

        assertEquals("Rejection comment is required", error.getMessage());
        verifyNoInteractions(smartEngine);
    }

    @Test
    void todoQueryIncludesCurrentMemberRoleCodesAsAssigneeGroups() {
        MetaContext.setContext(7L, 101L, "manager-user-pid", "wd_manager@example.com");
        MetaContext.setMemberId(11L);

        SmartEngine smartEngine = mock(SmartEngine.class);
        TaskQueryService taskQueryService = mock(TaskQueryService.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        when(smartEngine.getTaskQueryService()).thenReturn(taskQueryService);
        when(taskQueryService.findPendingTaskList(org.mockito.ArgumentMatchers.any(PendingTaskQueryParam.class)))
                .thenReturn(List.of());
        when(roleMapper.findByMemberIdAndTenantId(11L, 7L))
                .thenReturn(List.of(role("wd_manager"), role("workflow_operator")));
        TaskService service = new TaskService(
                smartEngine,
                mock(BpmAuditService.class),
                mock(BpmTaskActionsResolver.class),
                roleMapper
        );

        service.getTodoTasks("wd_manager@example.com");

        ArgumentCaptor<PendingTaskQueryParam> captor = ArgumentCaptor.forClass(PendingTaskQueryParam.class);
        verify(taskQueryService).findPendingTaskList(captor.capture());
        PendingTaskQueryParam query = captor.getValue();
        assertThat(query.getAssigneeUserId()).isEqualTo("wd_manager@example.com");
        assertThat(query.getTenantId()).isEqualTo("7");
        assertThat(query.getAssigneeGroupIdList()).containsExactly("wd_manager", "workflow_operator");
    }

    @Test
    void completedQueryIncludesCurrentMemberRoleCodesWhenCallerDoesNotProvideGroups() {
        MetaContext.setContext(7L, 101L, "manager-user-pid", "wd_manager@example.com");
        MetaContext.setMemberId(11L);

        SmartEngine smartEngine = mock(SmartEngine.class);
        TaskQueryService taskQueryService = mock(TaskQueryService.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        when(smartEngine.getTaskQueryService()).thenReturn(taskQueryService);
        when(taskQueryService.findTaskListByAssignee(org.mockito.ArgumentMatchers.any(TaskInstanceQueryByAssigneeParam.class)))
                .thenReturn(List.of());
        when(roleMapper.findByMemberIdAndTenantId(11L, 7L))
                .thenReturn(List.of(role("wd_manager")));
        TaskService service = new TaskService(
                smartEngine,
                mock(BpmAuditService.class),
                mock(BpmTaskActionsResolver.class),
                roleMapper
        );
        TaskInstanceQueryByAssigneeParam param = new TaskInstanceQueryByAssigneeParam();
        param.setAssigneeUserId("wd_manager@example.com");

        service.getCompletedTasks(param);

        ArgumentCaptor<TaskInstanceQueryByAssigneeParam> captor =
                ArgumentCaptor.forClass(TaskInstanceQueryByAssigneeParam.class);
        verify(taskQueryService).findTaskListByAssignee(captor.capture());
        TaskInstanceQueryByAssigneeParam query = captor.getValue();
        assertThat(query.getTenantId()).isEqualTo("7");
        assertThat(query.getAssigneeGroupIdList()).containsExactly("wd_manager");
    }

    @Test
    void approveAllowsCurrentMemberRoleToCompleteGroupCandidateTask() {
        MetaContext.setContext(7L, 101L, "manager-user-pid", "wd_manager@example.com");
        MetaContext.setMemberId(11L);

        SmartEngine smartEngine = mock(SmartEngine.class);
        TaskQueryService taskQueryService = mock(TaskQueryService.class);
        TaskCommandService taskCommandService = mock(TaskCommandService.class);
        VariableQueryService variableQueryService = mock(VariableQueryService.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        when(smartEngine.getTaskQueryService()).thenReturn(taskQueryService);
        when(smartEngine.getTaskCommandService()).thenReturn(taskCommandService);
        when(smartEngine.getVariableQueryService()).thenReturn(variableQueryService);
        when(variableQueryService.findProcessInstanceVariableList("pi-1", "7")).thenReturn(List.of());
        when(roleMapper.findByMemberIdAndTenantId(11L, 7L)).thenReturn(List.of(role("wd_manager")));

        DefaultTaskInstance task = new DefaultTaskInstance();
        task.setInstanceId("task-1");
        task.setProcessInstanceId("pi-1");
        task.setTaskAssigneeInstanceList(List.of(assignee("group", "wd_manager")));
        when(taskQueryService.findOne("task-1", "7")).thenReturn(task);

        TaskService service = new TaskService(
                smartEngine,
                mock(BpmAuditService.class),
                mock(BpmTaskActionsResolver.class),
                roleMapper
        );

        service.approveTask("task-1", "同意", Map.of());

        verify(taskCommandService).complete(eq("task-1"), anyMap());
    }

    private static Role role(String code) {
        Role role = new Role();
        role.setCode(code);
        return role;
    }

    private static DefaultTaskAssigneeInstance assignee(String type, String id) {
        DefaultTaskAssigneeInstance assignee = new DefaultTaskAssigneeInstance();
        assignee.setAssigneeType(type);
        assignee.setAssigneeId(id);
        return assignee;
    }
}
