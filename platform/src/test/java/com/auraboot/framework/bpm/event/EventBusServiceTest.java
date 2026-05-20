package com.auraboot.framework.bpm.event;

import com.auraboot.framework.bpm.entity.EventLogEntity;
import com.auraboot.framework.bpm.mapper.EventLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventBusServiceTest {

    @Mock
    private EventLogMapper eventLogMapper;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Test
    void publishPersistsEventWithoutPreselectingGeneratedEventId() {
        EventBusService service = new EventBusService(eventLogMapper, applicationEventPublisher);
        BpmEvent event = new BpmEvent(7L, "process_started", "bpm",
                "proc-key", "pi-1", null, Map.of("startUserId", "42"));
        AtomicInteger subscriberCalls = new AtomicInteger();
        service.subscribe("process_started", ignored -> subscriberCalls.incrementAndGet());

        service.publish(event);

        ArgumentCaptor<EventLogEntity> captor = ArgumentCaptor.forClass(EventLogEntity.class);
        verify(eventLogMapper).insert(captor.capture());
        verify(eventLogMapper, never()).findByEventId(event.getEventId());
        verify(applicationEventPublisher).publishEvent(event);
        assertThat(subscriberCalls.get()).isEqualTo(1);
        assertThat(captor.getValue().getEventId()).isEqualTo(event.getEventId());
        assertThat(captor.getValue().getTenantId()).isEqualTo(7L);
        assertThat(captor.getValue().getEventType()).isEqualTo("process_started");
    }

    @Test
    void publishSkipsDispatchWhenDatabaseUniqueConstraintReportsDuplicateEventId() {
        EventBusService service = new EventBusService(eventLogMapper, applicationEventPublisher);
        BpmEvent event = new BpmEvent(7L, "task_assigned", "bpm",
                "proc-key", "pi-1", "approve", Map.of());
        AtomicInteger subscriberCalls = new AtomicInteger();
        service.subscribe("task_assigned", ignored -> subscriberCalls.incrementAndGet());
        when(eventLogMapper.insert(any(EventLogEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate event_id"));

        service.publish(event);

        verify(eventLogMapper).insert(any(EventLogEntity.class));
        verify(eventLogMapper, never()).findByEventId(event.getEventId());
        verify(applicationEventPublisher, never()).publishEvent(any());
        assertThat(subscriberCalls.get()).isZero();
    }

    @Test
    void publishTransientDispatchesWithoutPersistingEventLog() {
        EventBusService service = new EventBusService(eventLogMapper, applicationEventPublisher);
        BpmEvent event = new BpmEvent(7L, "process_started", "bpm",
                null, "pi-1", null, Map.of("startUserId", "42"));
        AtomicInteger subscriberCalls = new AtomicInteger();
        service.subscribe("process_started", ignored -> subscriberCalls.incrementAndGet());

        service.publishTransient(event);

        verify(eventLogMapper, never()).insert(any(EventLogEntity.class));
        verify(eventLogMapper, never()).findByEventId(event.getEventId());
        verify(applicationEventPublisher).publishEvent(event);
        assertThat(subscriberCalls.get()).isEqualTo(1);
    }
}
