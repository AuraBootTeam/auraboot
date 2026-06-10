package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the task-level terminal signal (A3): every terminal
 * transition of ab_agent_task publishes {@link AgentTaskCompletedEvent}
 * carrying the parent task pid for delegation correlation.
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskCompletedEventFlowTest {

    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private AgentMemoryService memoryService;
    @Mock private AgentObservationService observationService;
    @Mock private LlmProviderFactory providerFactory;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ApplicationEventPublisher eventPublisher;

    private RunLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new RunLifecycleService(dynamicDataMapper, new ObjectMapper(),
                memoryService, observationService, providerFactory, jdbcTemplate, eventPublisher);
        // parent lookup for the published event
        lenient().when(dynamicDataMapper.selectByQuery(contains("SELECT parent_id"), anyMap()))
                .thenReturn(List.of(Map.of("parent_id", "parent-123")));
    }

    @Test
    void successfulRunPublishesDoneEventWithParent() {
        AgentRunService.AgentLoopResult result = new AgentRunService.AgentLoopResult();
        result.success = true;
        result.lastResponse = "all good";

        service.completeRunRecord(1L, "run-1", "task-1", LocalDateTime.now().minusSeconds(5), result, "m");

        ArgumentCaptor<org.springframework.context.ApplicationEvent> captor =
                ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        AgentTaskCompletedEvent event = (AgentTaskCompletedEvent) captor.getValue();
        assertThat(event.getTaskPid()).isEqualTo("task-1");
        assertThat(event.getParentTaskPid()).isEqualTo("parent-123");
        assertThat(event.getStatus()).isEqualTo("done");
    }

    @Test
    void failedRunPublishesBlockedEvent() {
        // failTask also sweeps children — return none
        when(dynamicDataMapper.selectByQuery(contains("task_status IN ('todo', 'backlog')"), anyMap()))
                .thenReturn(List.of());

        service.failTask(1L, "task-9", "boom");

        ArgumentCaptor<org.springframework.context.ApplicationEvent> captor =
                ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        AgentTaskCompletedEvent event = (AgentTaskCompletedEvent) captor.getValue();
        assertThat(event.getTaskPid()).isEqualTo("task-9");
        assertThat(event.getStatus()).isEqualTo("blocked");
    }

    @Test
    void failTaskPublishesCancelledEventsForPendingChildren() {
        when(dynamicDataMapper.selectByQuery(contains("task_status IN ('todo', 'backlog')"), anyMap()))
                .thenReturn(List.of(Map.of("pid", "child-1"), Map.of("pid", "child-2")));

        service.failTask(1L, "task-9", "boom");

        ArgumentCaptor<org.springframework.context.ApplicationEvent> captor =
                ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(3)).publishEvent(captor.capture());
        List<org.springframework.context.ApplicationEvent> events = captor.getAllValues();
        assertThat(events).hasSize(3);
        AgentTaskCompletedEvent cancelled = (AgentTaskCompletedEvent) events.get(1);
        assertThat(cancelled.getTaskPid()).isEqualTo("child-1");
        assertThat(cancelled.getStatus()).isEqualTo("cancelled");
        assertThat(cancelled.getParentTaskPid()).isEqualTo("task-9");
    }
}
