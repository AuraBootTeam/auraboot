package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.infrastructure.mq.memory.InMemoryMqProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the routing logic that turns a validated event into either a durable insert,
 * an idempotent no-op (duplicate), or a quarantine message. The DB boundary (mapper) is a mock
 * driving the insert outcome; the quarantine side uses the real publisher over the in-memory MQ
 * so we assert the actual quarantine envelope (reason + retained event), not a call count.
 */
class BehaviorEventPersisterTest {

    private BehaviorEventMapper mapper;
    private InMemoryMqProvider mq;
    private List<BehaviorQuarantineEnvelope> quarantined;
    private BehaviorEventPersister persister;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setup() {
        mapper = mock(BehaviorEventMapper.class);
        mq = new InMemoryMqProvider();
        quarantined = new ArrayList<>();
        mq.subscribe(BehaviorIngestPublisher.TOPIC_QUARANTINE, "test", (t, body, h) -> {
            try {
                quarantined.add(objectMapper.readValue(body, BehaviorQuarantineEnvelope.class));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        BehaviorIngestPublisher publisher = new BehaviorIngestPublisher(mq, objectMapper);
        persister = new BehaviorEventPersister(mapper, publisher, objectMapper);
    }

    private BehaviorEventInput event(String id, String name) {
        BehaviorEventInput in = new BehaviorEventInput();
        in.setEventId(id);
        in.setEventName(name);
        return in;
    }

    @Test
    void validEvent_inserts_andNotQuarantined() {
        when(mapper.insert(any(BehaviorEvent.class))).thenReturn(1);

        boolean stored = persister.persistOne(42L, 7L, event("01ABC", "page_view"));

        assertThat(stored).isTrue();
        verify(mapper).insert(any(BehaviorEvent.class));
        assertThat(quarantined).isEmpty();
    }

    @Test
    void duplicateEventId_isIdempotent_noQuarantine_noRethrow() {
        when(mapper.insert(any(BehaviorEvent.class))).thenThrow(new DuplicateKeyException("dup (tenant_id,event_id)"));

        boolean stored = persister.persistOne(42L, 7L, event("01ABC", "page_view"));

        assertThat(stored).isTrue(); // already durably stored — counts as stored, not quarantined
        assertThat(quarantined).isEmpty();
    }

    @Test
    void missingEventId_quarantined_notInserted() {
        boolean stored = persister.persistOne(42L, 7L, event(null, "page_view"));

        assertThat(stored).isFalse();
        verify(mapper, never()).insert(any(BehaviorEvent.class));
        assertThat(quarantined).hasSize(1);
        assertThat(quarantined.get(0).reason()).isEqualTo("malformed_missing_event_id");
        assertThat(quarantined.get(0).tenantId()).isEqualTo(42L);
        assertThat(quarantined.get(0).event().getEventName()).isEqualTo("page_view");
    }

    @Test
    void missingEventName_quarantined_notInserted() {
        boolean stored = persister.persistOne(42L, 7L, event("01ABC", null));

        assertThat(stored).isFalse();
        verify(mapper, never()).insert(any(BehaviorEvent.class));
        assertThat(quarantined).hasSize(1);
        assertThat(quarantined.get(0).reason()).isEqualTo("malformed_missing_event_name");
    }

    @Test
    void constraintViolation_quarantined_withReason_noRethrow() {
        when(mapper.insert(any(BehaviorEvent.class)))
                .thenThrow(new DataIntegrityViolationException("value too long for type character varying(40)"));

        boolean stored = persister.persistOne(42L, 7L, event("0123456789012345678901234567890123456789TOOLONG", "click"));

        assertThat(stored).isFalse();
        assertThat(quarantined).hasSize(1);
        assertThat(quarantined.get(0).reason()).isEqualTo("constraint_violation");
        assertThat(quarantined.get(0).detail()).contains("too long");
    }
}
