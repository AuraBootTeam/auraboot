package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.ingest.BehaviorIngestPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the decoupled collect service: both the authenticated path ({@code record},
 * tenant/user from the auth context) and the keyed/anonymous path ({@code recordAnonymous},
 * caller-resolved tenant, no user) now <b>validate + enqueue</b> via the ingest publisher
 * rather than persisting in the request thread. {@code accepted} == number enqueued.
 */
class BehaviorCollectServiceTest {

    private final BehaviorIngestPublisher publisher = mock(BehaviorIngestPublisher.class);
    private final BehaviorCollectService service = new BehaviorCollectService(publisher);

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    private BehaviorEventInput event(String id) {
        BehaviorEventInput in = new BehaviorEventInput();
        in.setEventId(id);
        in.setEventName("page_view");
        in.setAnonId("anon-123");
        return in;
    }

    @Test
    void recordAnonymous_enqueuesBatchWithTenant_userNull_returnsEnqueuedCount() {
        when(publisher.publish(eq(7001L), isNull(), anyList())).thenReturn(2);

        int accepted = service.recordAnonymous(List.of(event("e1"), event("e2")), 7001L);

        assertThat(accepted).isEqualTo(2);
        verify(publisher).publish(eq(7001L), isNull(), argThat(l -> l.size() == 2));
    }

    @Test
    void recordAnonymous_emptyOrNull_returnsZero_noPublish() {
        assertThat(service.recordAnonymous(List.of(), 7001L)).isZero();
        assertThat(service.recordAnonymous(null, 7001L)).isZero();
        verify(publisher, never()).publish(anyLong(), any(), anyList());
    }

    @Test
    void record_authPath_enqueuesWithTenantAndUserFromContext() {
        MetaContext.setCurrentTenantId(900L);
        MetaContext.setCurrentUserId(55L);
        when(publisher.publish(eq(900L), eq(55L), anyList())).thenReturn(1);

        int accepted = service.record(List.of(event("e1")));

        assertThat(accepted).isEqualTo(1);
        verify(publisher).publish(eq(900L), eq(55L), anyList());
    }

    @Test
    void record_noTenantContext_throwsUnauthorized_noPublish() {
        MetaContext.clear();

        assertThatThrownBy(() -> service.record(List.of(event("e1"))))
                .isInstanceOf(ResponseStatusException.class);
        verify(publisher, never()).publish(anyLong(), any(), anyList());
    }

    @Test
    void record_emptyBatch_returnsZero_withoutTenantCheck_noPublish() {
        MetaContext.clear(); // no tenant — an empty batch must short-circuit, not 401

        assertThat(service.record(List.of())).isZero();
        verify(publisher, never()).publish(anyLong(), any(), anyList());
    }
}
