package com.auraboot.framework.behavior.service;

import com.auraboot.framework.behavior.dto.BehaviorQuarantineReplayResult;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.auraboot.framework.behavior.entity.BehaviorQuarantine;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.behavior.mapper.BehaviorQuarantineMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BehaviorQuarantineServiceTest {

    private static final long TENANT = 42L;
    private static final long USER = 7L;

    @Mock
    private BehaviorQuarantineMapper quarantineMapper;
    @Mock
    private BehaviorEventMapper behaviorEventMapper;

    private BehaviorQuarantineService service;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setup() {
        service = new BehaviorQuarantineService(quarantineMapper, behaviorEventMapper, objectMapper);
    }

    @Test
    void replayOne_validPendingQuarantine_persistsBehaviorEventAndMarksReplayed() throws Exception {
        BehaviorQuarantine q = quarantine(10L, TENANT, USER, "constraint_violation",
                rawEvent("evt-replay-1", "page_view"));
        when(quarantineMapper.selectById(10L)).thenReturn(q);
        when(behaviorEventMapper.findIdByTenantAndEventId(TENANT, "evt-replay-1")).thenReturn(null);
        when(behaviorEventMapper.insert(any(BehaviorEvent.class))).thenAnswer(inv -> {
            BehaviorEvent event = inv.getArgument(0);
            event.setId(901L);
            return 1;
        });

        BehaviorQuarantineReplayResult result = service.replayOne(TENANT, 10L);

        assertThat(result.status()).isEqualTo("replayed");
        assertThat(result.eventId()).isEqualTo("evt-replay-1");
        assertThat(result.behaviorEventId()).isEqualTo(901L);

        ArgumentCaptor<BehaviorEvent> eventCap = ArgumentCaptor.forClass(BehaviorEvent.class);
        verify(behaviorEventMapper).insert(eventCap.capture());
        assertThat(eventCap.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(eventCap.getValue().getUserId()).isEqualTo(USER);
        assertThat(eventCap.getValue().getEventName()).isEqualTo("page_view");
        assertThat(eventCap.getValue().getProps()).contains("browser");

        ArgumentCaptor<BehaviorQuarantine> quarantineCap = ArgumentCaptor.forClass(BehaviorQuarantine.class);
        verify(quarantineMapper).updateById(quarantineCap.capture());
        assertThat(quarantineCap.getValue().getReplayStatus()).isEqualTo("replayed");
        assertThat(quarantineCap.getValue().getReplayedBehaviorEventId()).isEqualTo(901L);
        assertThat(quarantineCap.getValue().getReplayedAt()).isNotNull();
    }

    @Test
    void replayOne_existingEventId_marksDuplicateWithoutInsert() throws Exception {
        BehaviorQuarantine q = quarantine(11L, TENANT, null, "constraint_violation",
                rawEvent("evt-replay-dup", "click"));
        when(quarantineMapper.selectById(11L)).thenReturn(q);
        when(behaviorEventMapper.findIdByTenantAndEventId(TENANT, "evt-replay-dup")).thenReturn(777L);

        BehaviorQuarantineReplayResult result = service.replayOne(TENANT, 11L);

        assertThat(result.status()).isEqualTo("duplicate");
        assertThat(result.behaviorEventId()).isEqualTo(777L);
        verify(behaviorEventMapper, never()).insert(any(BehaviorEvent.class));

        ArgumentCaptor<BehaviorQuarantine> quarantineCap = ArgumentCaptor.forClass(BehaviorQuarantine.class);
        verify(quarantineMapper).updateById(quarantineCap.capture());
        assertThat(quarantineCap.getValue().getReplayStatus()).isEqualTo("duplicate");
        assertThat(quarantineCap.getValue().getReplayedBehaviorEventId()).isEqualTo(777L);
    }

    @Test
    void replayOne_malformedRawEvent_marksFailedWithoutInsert() {
        BehaviorQuarantine q = quarantine(12L, TENANT, null, "malformed_missing_event_name",
                "{\"eventId\":\"evt-no-name\"}");
        when(quarantineMapper.selectById(12L)).thenReturn(q);

        BehaviorQuarantineReplayResult result = service.replayOne(TENANT, 12L);

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.detail()).contains("event_name is required");
        verify(behaviorEventMapper, never()).insert(any(BehaviorEvent.class));

        ArgumentCaptor<BehaviorQuarantine> quarantineCap = ArgumentCaptor.forClass(BehaviorQuarantine.class);
        verify(quarantineMapper).updateById(quarantineCap.capture());
        assertThat(quarantineCap.getValue().getReplayStatus()).isEqualTo("failed");
        assertThat(quarantineCap.getValue().getReplayDetail()).contains("event_name is required");
    }

    private BehaviorQuarantine quarantine(Long id, Long tenantId, Long userId, String reason, String rawEvent) {
        BehaviorQuarantine q = new BehaviorQuarantine();
        q.setId(id);
        q.setTenantId(tenantId);
        q.setUserId(userId);
        q.setReason(reason);
        q.setRawEvent(rawEvent);
        q.setReplayStatus("pending");
        return q;
    }

    private String rawEvent(String eventId, String eventName) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventName", eventName,
                "anonId", "anon-replay",
                "props", Map.of("browser", "chromium")
        ));
    }
}
