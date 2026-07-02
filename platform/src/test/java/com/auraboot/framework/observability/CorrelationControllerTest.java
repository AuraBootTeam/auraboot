package com.auraboot.framework.observability;

import com.auraboot.framework.observability.dto.CorrelationView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link CorrelationController} (deep-review DR-20260701 R5-A2 test gap).
 * Thin read endpoint that delegates the trace-id lookup to {@link CorrelationQueryService}.
 */
@ExtendWith(MockitoExtension.class)
class CorrelationControllerTest {

    @Mock
    private CorrelationQueryService correlationQueryService;

    @InjectMocks
    private CorrelationController controller;

    @Test
    @DisplayName("byTrace delegates to the query service for the given trace id")
    void byTraceDelegatesToService() {
        CorrelationView view = new CorrelationView();
        when(correlationQueryService.byTrace("t1")).thenReturn(view);

        assertThat(controller.byTrace("t1")).isSameAs(view);
    }
}
