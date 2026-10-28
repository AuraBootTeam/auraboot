package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.exception.MetaRecordNotFoundException;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.permission.service.PermissionFacade;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RequiredRecordReadGuardTest {
    final MetaModelService models=mock(MetaModelService.class);
    final DynamicDataService data=mock(DynamicDataService.class);
    final PermissionFacade permissions=mock(PermissionFacade.class);
    final DynamicDataMapper rows=mock(DynamicDataMapper.class);
    final DataDomainService domains=mock(DataDomainService.class);
    final QueryBuilderService queries=new QueryBuilderServiceImpl(rows);
    final RequiredRecordReadGuard guard=new RequiredRecordReadGuard(models,data,permissions,queries,rows,domains);
    static String id(int n) { return RequiredReadReferencesTest.id(n); }
    static Map<String,Object> root(int n, String... refs) { return Map.of("pid",id(n),"tenant_id",7L,"members",List.of(refs)); }
    @BeforeEach void actor() {
        MetaContext.setContext(7L,42L,"actor","Actor");MetaContext.setMemberId(43L);
        when(models.getModelDefinition("fixture_bundle")).thenReturn(Optional.of(RequiredReadReferencesTest.model(10)));
    }
    @AfterEach void clear() { MetaContext.clear(); }
    void allow() {
        when(permissions.canAction(43L,"fixture_member","read")).thenReturn(true);
        when(data.getById(eq("fixture_member"),anyString())).thenReturn(Map.of("pid",id(1)));
    }
    @Test void allMembersUseCurrentActorWithoutOuterPermitAndRestoreExactVersionAfterward() {
        allow();
        when(data.getById(eq("fixture_member"),anyString())).thenAnswer(call->{
            assertThat(MetaContext.hasCommandPermitScope()).isFalse(); assertThat(MetaContext.getCurrentTenantId()).isEqualTo(7L);
            assertThat(MetaContext.getCurrentMemberId()).isEqualTo(43L); return Map.of("pid",call.getArgument(1)); });
        MetaContext.runWithCommandPermitPlan("ALL",9L,"fixture_bundle",id(9),()->{
            guard.require("fixture_bundle",root(9,id(1),id(2)));
            assertThat(MetaContext.getCommandPermitScopeFor("fixture_bundle")).isEqualTo("ALL");
            assertThat(MetaContext.getCommandExpectedVersion("fixture_bundle",id(9))).isEqualTo(9L);
        });
        verify(data).getById("fixture_member",id(1));verify(data).getById("fixture_member",id(2));
    }
    @Test void revocationIsRecheckedAndFailuresRestoreOuterPermit() {
        allow();guard.require("fixture_bundle",root(9,id(1),id(2)));
        when(data.getById("fixture_member",id(2))).thenThrow(new AccessDeniedException("revoked"));
        MetaContext.runWithCommandPermitPlan("ALL",9L,"fixture_bundle",id(9),()->{
            assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1),id(2)))).isInstanceOf(AccessDeniedException.class);
            assertThat(MetaContext.getCommandPermitScopeFor("fixture_bundle")).isEqualTo("ALL");
        });
        verify(data,times(2)).getById("fixture_member",id(2));
    }
    @Test void missingFunctionPermissionWrongTenantAndMissingMemberNeverBecomeAllows() {
        assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1)))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(data);
        assertThatThrownBy(()->guard.require("fixture_bundle",Map.of("pid",id(9),"tenant_id",8L,"members",List.of(id(1))))).isInstanceOf(AccessDeniedException.class);
        MetaContext.setMemberId(null);
        assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1)))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void recursiveClosureIsRejectedAndDoesNotPoisonLaterReads() {
        allow();when(data.getById("fixture_member",id(1))).thenAnswer(call->{guard.require("fixture_bundle",root(9,id(1)));return Map.of();});
        assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1)))).isInstanceOf(MetaServiceException.class).hasMessageContaining("cycle");
        reset(data);allow();guard.require("fixture_bundle",root(9,id(1)));
    }
    @Test void sqlPredicateContainsOnlyCompleteAuthorizedRootsAndBaseScopes() {
        allow();when(domains.buildDomainFilter("fixture_bundle",42L)).thenReturn("AND domain_id = 5");
        when(rows.selectByQuery(anyString(),anyMap())).thenAnswer(call->{
            assertThat((String)call.getArgument(0)).contains("tenant_id", "created_by = 42", "domain_id = 5", "LIMIT 11");
            return List.of(root(9,id(1)),root(10,id(2))); });
        when(data.getById("fixture_member",id(2))).thenThrow(new AccessDeniedException("revoked"));
        assertThat(guard.sqlFilter(7L,"fixture_bundle",42L,"AND created_by = 42")).isEqualTo("AND pid IN ('"+id(9)+"')");
        when(data.getById("fixture_member",id(1))).thenThrow(new MetaRecordNotFoundException("fixture_member",id(1)));
        assertThat(guard.sqlFilter(7L,"fixture_bundle",42L,"AND created_by = 42")).isEqualTo("AND 1 = 0");
    }
    @Test void candidateOverflowAndInternalFailuresAbortInsteadOfReturningPartialVisibility() {
        when(rows.selectByQuery(anyString(),anyMap())).thenReturn(java.util.Collections.nCopies(11,root(9,id(1))));
        assertThatThrownBy(()->guard.sqlFilter(7L,"fixture_bundle",42L,"")).isInstanceOf(MetaServiceException.class).hasMessageContaining("budget");
        verifyNoInteractions(data);
        allow();when(rows.selectByQuery(anyString(),anyMap())).thenReturn(List.of(root(9,id(1))));
        when(data.getById("fixture_member",id(1))).thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(()->guard.sqlFilter(7L,"fixture_bundle",42L,"")).isInstanceOf(IllegalStateException.class).hasMessage("storage unavailable");
    }
    @Test void referenceWorkBudgetAbortsTheEntireScanAndRestoresTraversalLifetime() {
        when(models.getModelDefinition("fixture_bundle")).thenReturn(Optional.of(RequiredReadReferencesTest.model(20)));
        allow();
        var members=java.util.stream.IntStream.rangeClosed(1,256).mapToObj(RequiredRecordReadGuardTest::id).toList();
        var roots=java.util.stream.IntStream.range(500,520).mapToObj(n->Map.<String,Object>of("pid",id(n),"tenant_id",7L,"members",members)).toList();
        when(rows.selectByQuery(anyString(),anyMap())).thenReturn(roots);
        assertThatThrownBy(()->guard.sqlFilter(7L,"fixture_bundle",42L,"")).isInstanceOf(MetaServiceException.class).hasMessageContaining("reference budget");
        guard.require("fixture_bundle",root(9,id(1)));
    }
}
