package com.auraboot.framework.bpm.config;

import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.bpm.event.EventBusService;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.assembly.ProcessDefinition;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.smart.framework.engine.pvm.event.EventConstant;
import com.auraboot.smart.framework.engine.service.query.RepositoryQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuraTaskEventPublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void enrichesProcessNameFromSmartEngineCacheWithoutDatabaseLookup() {
        EventBusService eventBusService = mock(EventBusService.class);
        BpmProcessDefinitionMapper processDefinitionMapper = mock(BpmProcessDefinitionMapper.class);
        UserService userService = mock(UserService.class);
        ObjectProvider<SmartEngine> smartEngineProvider = mock(ObjectProvider.class);
        SmartEngine smartEngine = mock(SmartEngine.class);
        RepositoryQueryService repositoryQueryService = mock(RepositoryQueryService.class);
        ProcessDefinition processDefinition = mock(ProcessDefinition.class);
        TaskInstance taskInstance = mock(TaskInstance.class);

        when(smartEngineProvider.getIfAvailable()).thenReturn(smartEngine);
        when(smartEngine.getRepositoryQueryService()).thenReturn(repositoryQueryService);
        when(repositoryQueryService.getCachedProcessDefinition("expense", "1", "1"))
                .thenReturn(processDefinition);
        when(processDefinition.getName()).thenReturn("Expense Approval");
        when(taskInstance.getInstanceId()).thenReturn("task-1");
        when(taskInstance.getTag()).thenReturn("Approve");
        when(taskInstance.getProcessDefinitionActivityId()).thenReturn("approve");
        when(taskInstance.getProcessDefinitionIdAndVersion()).thenReturn("expense:1");
        when(taskInstance.getProcessInstanceId()).thenReturn("pi-1");

        AuraTaskEventPublisher publisher = new AuraTaskEventPublisher(
                eventBusService,
                processDefinitionMapper,
                userService,
                smartEngineProvider
        );

        publisher.publish(EventConstant.TASK_ASSIGNED, taskInstance, "1", Map.of("assigneeIds", "42"));

        verify(processDefinitionMapper, never()).selectOne(any());
        ArgumentCaptor<BpmEvent> eventCaptor = ArgumentCaptor.forClass(BpmEvent.class);
        verify(eventBusService).publish(eventCaptor.capture());
        assertEquals("Expense Approval", eventCaptor.getValue().getPayload().get("processName"));
    }
}
