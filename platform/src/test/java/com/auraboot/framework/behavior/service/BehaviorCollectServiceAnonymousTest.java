package com.auraboot.framework.behavior.service;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link BehaviorCollectService#recordAnonymous} — the SP2 keyed/anonymous
 * ingestion path. The tenant comes from the (caller-resolved) site key, not the auth context;
 * there is no user; the client-supplied anonId is the only identity.
 */
class BehaviorCollectServiceAnonymousTest {

    private final BehaviorEventMapper mapper = mock(BehaviorEventMapper.class);
    private final BehaviorCollectService service =
            new BehaviorCollectService(mapper, new ObjectMapper());

    private BehaviorEventInput event(String id) {
        BehaviorEventInput in = new BehaviorEventInput();
        in.setEventId(id);
        in.setEventName("page_view");
        in.setAnonId("anon-123");
        return in;
    }

    @Test
    void recordAnonymous_setsTenantFromArg_userNull_anonIdPassedThrough() {
        int accepted = service.recordAnonymous(List.of(event("e1")), 7001L);

        assertThat(accepted).isEqualTo(1);
        ArgumentCaptor<BehaviorEvent> cap = ArgumentCaptor.forClass(BehaviorEvent.class);
        verify(mapper).insert(cap.capture());
        BehaviorEvent e = cap.getValue();
        assertThat(e.getTenantId()).isEqualTo(7001L);
        assertThat(e.getUserId()).isNull();
        assertThat(e.getAnonId()).isEqualTo("anon-123");
    }

    @Test
    void recordAnonymous_emptyOrNull_returnsZero_noInsert() {
        assertThat(service.recordAnonymous(List.of(), 7001L)).isZero();
        assertThat(service.recordAnonymous(null, 7001L)).isZero();
        verify(mapper, never()).insert(any(BehaviorEvent.class));
    }

    @Test
    void recordAnonymous_skipsMalformed_missingEventIdOrName() {
        BehaviorEventInput bad = new BehaviorEventInput(); // no id/name
        assertThat(service.recordAnonymous(List.of(bad), 7001L)).isZero();
        verify(mapper, never()).insert(any(BehaviorEvent.class));
    }
}
