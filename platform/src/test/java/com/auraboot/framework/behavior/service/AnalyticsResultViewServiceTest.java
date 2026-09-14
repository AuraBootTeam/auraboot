package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyticsResultViewServiceTest {
    private final BehaviorEventMapper events = mock(BehaviorEventMapper.class);
    private final AnalyticsJourneyService journey = mock(AnalyticsJourneyService.class);
    private final AnalyticsResultViewService service = new AnalyticsResultViewService(events, journey);

    @AfterEach
    void clear() { MetaContext.clear(); }

    @Test
    void recordsOnlyTheCurrentViewersSuccessfulQuery() {
        MetaContext.setContext(42L, 7L, "viewer", "viewer");
        var id = UUID.randomUUID();
        when(events.findSuccessfulQueryHash(42L, 7L, id.toString())).thenReturn("hash");
        service.record(id);
        verify(journey).resultViewed(id.toString(), "hash");
    }

    @Test
    void absentOrUnownedQueryCannotClaimPresentation() {
        MetaContext.setContext(42L, 8L, "other", "other");
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> service.record(id)).isInstanceOf(BusinessException.class);
        verify(events).findSuccessfulQueryHash(42L, 8L, id.toString());
        verifyNoInteractions(journey);
    }
}
