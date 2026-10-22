package com.auraboot.framework.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.verify;

/** Verifies tenant propagation and truthful cancellation results. */
@ExtendWith(MockitoExtension.class)
class InterruptDispatcherTenantScopeTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RunLifecycleService runLifecycleService;
    @Mock private SubAgentRunner subAgentRunner;

    @InjectMocks private InterruptDispatcher dispatcher;

    @Test
    @DisplayName("REPLACE_INTENT delegates the caller tenant and reports a rejected cancel as noop")
    void cancelRunUpdateIsTenantScoped() {
        InterruptClassifier.Classification replace = InterruptClassifier.Classification.builder()
                .subPolicy(InterruptClassifier.REPLACE_INTENT)
                .confidence(0.9)
                .tier("keyword")
                .reason("test")
                .build();

        var result = dispatcher.dispatch(7L, "session-1", "run-from-tenant-B", "stop", replace);
        verify(runLifecycleService).cancelRun(7L, "run-from-tenant-B");
        org.assertj.core.api.Assertions.assertThat(result.getActionTaken()).isEqualTo("noop");
        org.mockito.Mockito.verifyNoInteractions(eventPublisher);
    }
}
