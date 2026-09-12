package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.service.ReportAggregateQueryService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyticsExecutionSourceServiceTest {
    private final DynamicDataService data = mock(DynamicDataService.class);
    private final ReportAggregateQueryService queries = mock(ReportAggregateQueryService.class);
    private final UserPermissionService permissions = mock(UserPermissionService.class);
    private final AnalyticsExecutionSourceService service = new AnalyticsExecutionSourceService(data, queries, permissions, new ObjectMapper());
    @BeforeEach void setup() { MetaContext.setContext(1L, 2L, "user", "user"); }
    @AfterEach void close() { MetaContext.clear(); }
    @Test void missingExecutionPermissionCannotReadTheSuggestion() {
        assertThatThrownBy(() -> service.resolve("a".repeat(26)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(data, queries);
    }
    @Test void anotherUsersAdoptionCannotAuthorizeExecution() {
        when(permissions.hasPermission(2L, "analytics.suggestion.execute")).thenReturn(true);
        when(data.getById(AnalyticsSuggestionCommandHandler.ADOPTION, "a".repeat(26)))
                .thenReturn(Map.of("created_by", 9L));
        assertThatThrownBy(() -> service.resolve("a".repeat(26))).hasMessageContaining("unavailable to this user");
        verifyNoInteractions(queries);
    }
}
