package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.*;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.access.AccessDeniedException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyticsRecordAccessBoundaryTest {
    private final AnalyticsRecordAccessBoundary boundary = new AnalyticsRecordAccessBoundary();
    private final DynamicDataService target = mock(DynamicDataService.class);
    private DynamicDataService data;
    @BeforeEach void setup() {
        MetaContext.setContext(1L, 2L, "user", "user");
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(boundary);
        data = factory.getProxy();
    }
    @AfterEach void close() { MetaContext.clear(); }
    @Test void genericDetailAndListCannotReadRawFacts() {
        assertThatThrownBy(() -> data.getById(AnalyticsSuggestionCommandHandler.ADOPTION, "pid"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> data.list(AnalyticsSuggestionCommandHandler.VERSION, null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(target);
    }
    @Test void unrelatedModelsStillReachTheExistingPermissionPipeline() {
        when(target.getById("order", "pid")).thenReturn(Map.of("pid", "pid"));
        assertThat(data.getById("order", "pid")).containsEntry("pid", "pid");
    }
    @Test void domainScopeClosesOnFailureAndRejectsPrincipalChanges() throws Throwable {
        var operation = mock(org.aspectj.lang.ProceedingJoinPoint.class);
        when(operation.proceed()).thenAnswer(call -> {
            data.getById(AnalyticsSuggestionCommandHandler.ADOPTION, "pid");
            MetaContext.setContext(1L, 3L, "peer", "peer");
            assertThatThrownBy(() -> data.getById(AnalyticsSuggestionCommandHandler.ADOPTION, "pid"))
                    .isInstanceOf(AccessDeniedException.class);
            throw new IllegalStateException("failed domain operation");
        });
        assertThatThrownBy(() -> boundary.domainOperation(operation)).isInstanceOf(IllegalStateException.class);
        verify(target).getById(AnalyticsSuggestionCommandHandler.ADOPTION, "pid");
        MetaContext.setContext(1L, 2L, "user", "user");
        assertThatThrownBy(() -> data.getById(AnalyticsSuggestionCommandHandler.ADOPTION, "pid"))
                .isInstanceOf(AccessDeniedException.class);
    }
    @Test void semanticResolutionBlocksPrivateModelsAndAlwaysClosesScope() throws Throwable {
        var metadata = mock(com.auraboot.framework.meta.service.MetaModelService.class);
        when(metadata.getTableName("order")).thenReturn("mt_order");
        var factory = new AspectJProxyFactory(metadata);
        factory.addAspect(boundary);
        com.auraboot.framework.meta.service.MetaModelService guarded = factory.getProxy();
        var operation = mock(org.aspectj.lang.ProceedingJoinPoint.class);
        when(operation.proceed()).thenAnswer(call -> {
            assertThat(guarded.getTableName("order")).isEqualTo("mt_order");
            return guarded.getTableName(AnalyticsSuggestionCommandHandler.VERSION);
        });
        assertThatThrownBy(() -> boundary.semanticOperation(operation)).isInstanceOf(AccessDeniedException.class);
        verify(metadata, never()).getTableName(AnalyticsSuggestionCommandHandler.VERSION);
        guarded.getTableName(AnalyticsSuggestionCommandHandler.VERSION);
        verify(metadata).getTableName(AnalyticsSuggestionCommandHandler.VERSION);
    }
    @Test void namedSqlCountsCannotExposePrivateTablesAndScopeCloses() throws Throwable {
        var metadata = mock(com.auraboot.framework.meta.service.MetaModelService.class);
        when(metadata.getModelDefinition(anyString())).thenReturn(java.util.Optional.empty());
        org.springframework.test.util.ReflectionTestUtils.setField(boundary, "metadata", metadata);
        var mapper = mock(com.auraboot.framework.meta.mapper.DynamicDataMapper.class);
        var factory = new AspectJProxyFactory(mapper);
        factory.addAspect(boundary);
        com.auraboot.framework.meta.mapper.DynamicDataMapper guarded = factory.getProxy();
        String sql = "SELECT count(*) FROM (SELECT pid FROM public.mt_core_dashboard_adoption) x";
        var operation = mock(org.aspectj.lang.ProceedingJoinPoint.class);
        when(operation.proceed()).thenAnswer(call -> guarded.countByQueryWithoutTenant(sql, Map.of()));
        assertThatThrownBy(() -> boundary.namedOperation(operation)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(mapper);
        guarded.countByQueryWithoutTenant(sql, Map.of());
        verify(mapper).countByQueryWithoutTenant(sql, Map.of());
    }
}
