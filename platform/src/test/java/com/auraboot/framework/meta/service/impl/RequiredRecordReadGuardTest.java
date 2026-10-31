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
    void diamond(boolean immutable, String sourceType) {
        var branch=RequiredReadReferencesTest.model(10); branch.setCode("fixture_member");
        branch.setExtension(Map.of(RequiredReadReferences.KEY,Map.of("version",1,"maxCandidateRecords",10,"minimumReferences",1,
                "references",List.of(Map.of("field","members","model","fixture_leaf","many",true,"allowEmpty",false)))));
        when(models.getModelDefinition("fixture_member")).thenReturn(Optional.of(branch));
        when(models.getModelDefinition("fixture_leaf")).thenReturn(Optional.of(
                com.auraboot.framework.meta.dto.ModelDefinition.builder().code("fixture_leaf").immutable(immutable).sourceType(sourceType).build()));
        when(permissions.canAction(eq(43L),anyString(),eq("read"))).thenReturn(true);
        doAnswer(call->{
            var row=root(call.getArgument(1).equals(id(1))?1:2,id(3));guard.require("fixture_member",row);return row;
        }).when(data).getById(eq("fixture_member"),anyString());
        doReturn(root(3)).when(data).getById("fixture_leaf",id(3));
    }
    @Test void immutablePhysicalTerminalIsReadOncePerDiamondButEveryEdgeIsAuthorized() {
        diamond(true,"physical");guard.require("fixture_bundle",root(9,id(1),id(2)));
        verify(data,times(1)).getById("fixture_leaf",id(3));
        verify(permissions,times(2)).canAction(43L,"fixture_leaf","read");
        doThrow(new AccessDeniedException("revoked")).when(data).getById("fixture_leaf",id(3));
        assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1),id(2)))).isInstanceOf(AccessDeniedException.class);
        verify(data,times(2)).getById("fixture_leaf",id(3));
    }
    @Test void terminalProofNeverCrossesSqlCandidates() {
        diamond(true,null);when(rows.selectByQuery(anyString(),anyMap())).thenReturn(List.of(root(9,id(1),id(2)),root(10,id(1),id(2))));
        assertThat(guard.sqlFilter(7L,"fixture_bundle",42L,"")).contains(id(9),id(10));
        verify(data,times(2)).getById("fixture_leaf",id(3));
    }
    @Test void mutableTerminalsRetainEveryOrdinaryRead() {
        diamond(false,"physical");guard.require("fixture_bundle",root(9,id(1),id(2)));
        verify(data,times(2)).getById("fixture_leaf",id(3));
    }
    @Test void virtualTerminalsRetainEveryOrdinaryRead() {
        diamond(true,"remote");guard.require("fixture_bundle",root(9,id(1),id(2)));
        verify(data,times(2)).getById("fixture_leaf",id(3));
    }
    @Test void viewTerminalsRetainEveryOrdinaryRead() {
        diamond(true,null);var leaf=models.getModelDefinition("fixture_leaf").orElseThrow();leaf.setModelType("view");
        guard.require("fixture_bundle",root(9,id(1),id(2)));
        verify(data,times(2)).getById("fixture_leaf",id(3));
    }
    @Test void alternativePrimaryKeyTerminalsRetainEveryOrdinaryRead() {
        diamond(true,"physical");var leaf=models.getModelDefinition("fixture_leaf").orElseThrow();leaf.setPrimaryKey("external_key");
        guard.require("fixture_bundle",root(9,id(1),id(2)));
        verify(data,times(2)).getById("fixture_leaf",id(3));
    }
    @Test void nestedSqlCandidatesNeverShareATerminalProof() {
        diamond(true,"physical");when(rows.selectByQuery(anyString(),anyMap())).thenReturn(List.of(root(10,id(3)),root(11,id(3))));
        doAnswer(call->{guard.sqlFilter(7L,"fixture_member",42L,"");return root(1,id(3));}).when(data).getById("fixture_member",id(1));
        guard.require("fixture_bundle",root(9,id(1)));
        verify(data,times(2)).getById("fixture_leaf",id(3));
    }
    @Test void failedDiamondNeverLeavesATerminalProofForTheNextRoot() {
        diamond(true,"physical");doThrow(new IllegalStateException("unavailable")).when(data).getById("fixture_member",id(2));
        assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1),id(2)))).isInstanceOf(IllegalStateException.class);
        doThrow(new AccessDeniedException("revoked")).when(data).getById("fixture_leaf",id(3));
        assertThatThrownBy(()->guard.require("fixture_bundle",root(10,id(1)))).isInstanceOf(AccessDeniedException.class);
        verify(data,times(2)).getById("fixture_leaf",id(3));
    }
    @Test void repeatedTerminalStillChecksEveryFunctionPermission() {
        diamond(true,"physical");when(permissions.canAction(43L,"fixture_leaf","read")).thenReturn(true,false);
        assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1),id(2)))).isInstanceOf(AccessDeniedException.class);
        verify(data).getById("fixture_leaf",id(3));
        verify(permissions,times(2)).canAction(43L,"fixture_leaf","read");
    }
    @Test void terminalReuseDoesNotDiscountTheOriginalLogicalEdgeBudget() {
        diamond(true,"physical");when(models.getModelDefinition("fixture_bundle")).thenReturn(Optional.of(RequiredReadReferencesTest.model(1100)));
        var candidates=java.util.stream.IntStream.range(500,1600).mapToObj(n->root(n,id(1),id(2))).toList();
        when(rows.selectByQuery(anyString(),anyMap())).thenReturn(candidates);
        assertThatThrownBy(()->guard.sqlFilter(7L,"fixture_bundle",42L,"")).isInstanceOf(MetaServiceException.class).hasMessageContaining("reference budget");
        guard.require("fixture_bundle",root(9,id(1),id(2)));
    }
    @Test void exactQueryCandidatesDoNotTraverseUnrelatedRootsOrRelaxTheirBudget() {
        diamond(true,"physical");when(models.getModelDefinition("fixture_bundle")).thenReturn(Optional.of(RequiredReadReferencesTest.model(1100)));
        var unrelated=java.util.stream.IntStream.range(500,1600).mapToObj(n->root(n,id(1),id(2))).toList();
        when(rows.selectByQuery(anyString(),anyMap())).thenAnswer(call->{
            Map<String,Object> params=call.getArgument(1);
            return params.containsValue(id(9)) ? List.of(root(9,id(1),id(2))) : unrelated;
        });
        var condition=com.auraboot.framework.meta.dto.QueryCondition.builder().fieldName("pid")
                .operator(com.auraboot.framework.meta.dto.QueryCondition.Operator.EQ).value(id(9)).build();
        assertThat(guard.sqlFilter(7L,"fixture_bundle",42L,"",true,List.of(condition),null)).contains(id(9));
        assertThatThrownBy(()->guard.sqlFilter(7L,"fixture_bundle",42L,"",true,List.of(),null))
                .isInstanceOf(MetaServiceException.class).hasMessageContaining("reference budget");
    }
    @Test void keywordUsesTheCanonicalCandidateCompilerInsteadOfAnUnfilteredScan() {
        allow();var model=RequiredReadReferencesTest.model(10);var fields=new java.util.ArrayList<>(model.getFields());
        fields.add(com.auraboot.framework.meta.dto.FieldDefinition.builder().code("name").columnName("name").dataType("string").searchable(true).build());
        model.setFields(fields);when(models.getModelDefinition("fixture_bundle")).thenReturn(Optional.of(model));
        when(rows.selectByQuery(anyString(),anyMap())).thenAnswer(call->{
            assertThat((String)call.getArgument(0)).contains("name");
            assertThat((Map<String,Object>)call.getArgument(1)).containsValue("%chosen%");
            return List.of(root(9,id(1)));
        });
        assertThat(guard.sqlFilter(7L,"fixture_bundle",42L,"",true,List.of(),"chosen")).contains(id(9));
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
    @Test void nestedCanonicalFailureRestoresTheOuterOperationAndClosesTheInnerOne() {
        allow();
        RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(9),()->{
            guard.require("fixture_bundle",root(9,id(1)));RequiredRecordReadGuard.completePhysicalCanonicalRead("fixture_bundle",id(9));
            RequiredRecordReadGuard.finalizeCanonicalRead("fixture_bundle",id(9),()->{
                assertThatThrownBy(()->RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(10),()->{
                    guard.require("fixture_bundle",root(10,id(2)));RequiredRecordReadGuard.completePhysicalCanonicalRead("fixture_bundle",id(10));
                    RequiredRecordReadGuard.finalizeCanonicalRead("fixture_bundle",id(10),()->{throw new IllegalStateException("inner failed");});
                })).isInstanceOf(IllegalStateException.class).hasMessage("inner failed");
                guard.require("fixture_bundle",root(9,id(1)));
            });
        });
        verify(data,times(2)).getById("fixture_member",id(1));
        guard.require("fixture_bundle",root(10,id(2)));verify(data,times(2)).getById("fixture_member",id(2));
    }
    @Test void nestedMutationInvalidatesBothCanonicalOperations() {
        allow();
        RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(9),()->{
            guard.require("fixture_bundle",root(9,id(1)));RequiredRecordReadGuard.completePhysicalCanonicalRead("fixture_bundle",id(9));
            RequiredRecordReadGuard.finalizeCanonicalRead("fixture_bundle",id(9),()->{
                RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(10),()->{
                    guard.require("fixture_bundle",root(10,id(2)));RequiredRecordReadGuard.completePhysicalCanonicalRead("fixture_bundle",id(10));
                    RequiredRecordReadGuard.finalizeCanonicalRead("fixture_bundle",id(10),()->{
                        DynamicDataQueryScope.invalidateFiltersAfterMutation();guard.require("fixture_bundle",root(10,id(2)));
                    });
                });
                guard.require("fixture_bundle",root(9,id(1)));
            });
        });
        verify(data,times(2)).getById("fixture_member",id(1));verify(data,times(2)).getById("fixture_member",id(2));
    }
    @Test void canonicalOperationCannotSupplyAProofToAnotherRoot() {
        allow();
        RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(9),()->{
            guard.require("fixture_bundle",root(9,id(1)));RequiredRecordReadGuard.completePhysicalCanonicalRead("fixture_bundle",id(9));
            RequiredRecordReadGuard.finalizeCanonicalRead("fixture_bundle",id(9),()->guard.require("fixture_bundle",root(10,id(1))));
        });
        verify(data,times(2)).getById("fixture_member",id(1));
    }
    @Test void commandPermitKeepsBothOriginalDependencyTraversals() {
        allow();
        MetaContext.runWithCommandPermitPlan("ALL",9L,"fixture_bundle",id(9),()->{
            RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(9),()->{
                guard.require("fixture_bundle",root(9,id(1)));RequiredRecordReadGuard.completePhysicalCanonicalRead("fixture_bundle",id(9));
                RequiredRecordReadGuard.finalizeCanonicalRead("fixture_bundle",id(9),()->guard.require("fixture_bundle",root(9,id(1))));
            });
        });
        verify(data,times(2)).getById("fixture_member",id(1));
    }
    @Test void anInvisibleSqlCandidateInvalidatesTheCanonicalWitnessBeforeFinalAuthorization() {
        allow();when(rows.selectByQuery(anyString(),anyMap())).thenReturn(List.of(root(9,id(1))));
        RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(9),()->{
            guard.require("fixture_bundle",root(9,id(1)));RequiredRecordReadGuard.completePhysicalCanonicalRead("fixture_bundle",id(9));
            RequiredRecordReadGuard.finalizeCanonicalRead("fixture_bundle",id(9),()->{
                doThrow(new AccessDeniedException("revoked")).when(data).getById("fixture_member",id(1));
                assertThat(guard.sqlFilter(7L,"fixture_bundle",42L,"")).isEqualTo("AND 1 = 0");
                assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1)))).isInstanceOf(AccessDeniedException.class);
            });
        });
    }
    @Test void caughtNestedDependencyDenialInvalidatesTheParentCanonicalWitness() {
        allow();
        RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(9),()->{
            guard.require("fixture_bundle",root(9,id(1)));RequiredRecordReadGuard.completePhysicalCanonicalRead("fixture_bundle",id(9));
            RequiredRecordReadGuard.finalizeCanonicalRead("fixture_bundle",id(9),()->{
                doThrow(new AccessDeniedException("revoked")).when(data).getById("fixture_member",id(1));
                assertThatThrownBy(()->RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(10),()->
                        guard.require("fixture_bundle",root(10,id(1))))).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1)))).isInstanceOf(AccessDeniedException.class);
            });
        });
    }
    @Test void caughtCandidateBudgetFailureCannotLeaveACanonicalWitnessUsable() {
        allow();when(rows.selectByQuery(anyString(),anyMap())).thenReturn(java.util.Collections.nCopies(11,root(10,id(1))));
        RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(9),()->{
            guard.require("fixture_bundle",root(9,id(1)));RequiredRecordReadGuard.completePhysicalCanonicalRead("fixture_bundle",id(9));
            RequiredRecordReadGuard.finalizeCanonicalRead("fixture_bundle",id(9),()->{
                assertThatThrownBy(()->guard.sqlFilter(7L,"fixture_bundle",42L,"")).isInstanceOf(MetaServiceException.class).hasMessageContaining("budget");
                guard.require("fixture_bundle",root(9,id(1)));
            });
        });
        verify(data,times(2)).getById("fixture_member",id(1));
    }
    @Test void anotherGuardCannotConsumeTheCanonicalWitnessEvenForTheSameIdentity() {
        allow();var otherPermissions=mock(PermissionFacade.class);
        var other=new RequiredRecordReadGuard(models,data,otherPermissions,queries,rows,domains);
        RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(9),()->{
            guard.require("fixture_bundle",root(9,id(1)));RequiredRecordReadGuard.completePhysicalCanonicalRead("fixture_bundle",id(9));
            RequiredRecordReadGuard.finalizeCanonicalRead("fixture_bundle",id(9),()->
                    assertThatThrownBy(()->other.require("fixture_bundle",root(9,id(1)))).isInstanceOf(AccessDeniedException.class));
        });
    }
    @Test void preGuardNestedFailureInvalidatesTheParentWitness() {
        allow();
        RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(9),()->{
            guard.require("fixture_bundle",root(9,id(1)));RequiredRecordReadGuard.completePhysicalCanonicalRead("fixture_bundle",id(9));
            RequiredRecordReadGuard.finalizeCanonicalRead("fixture_bundle",id(9),()->{
                doThrow(new AccessDeniedException("revoked")).when(data).getById("fixture_member",id(1));
                assertThatThrownBy(()->RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(10),()->{
                    throw new AccessDeniedException("pre-guard denial");
                })).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1)))).isInstanceOf(AccessDeniedException.class);
            });
        });
    }
    @Test void earlyPermitFallbackFailureInvalidatesTheParentWitness() {
        allow();
        RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(9),()->{
            guard.require("fixture_bundle",root(9,id(1)));RequiredRecordReadGuard.completePhysicalCanonicalRead("fixture_bundle",id(9));
            RequiredRecordReadGuard.finalizeCanonicalRead("fixture_bundle",id(9),()->{
                doThrow(new AccessDeniedException("revoked")).when(data).getById("fixture_member",id(1));
                assertThatThrownBy(()->MetaContext.runWithCommandPermitPlan("ALL",1L,"fixture_bundle",id(10),()->
                        RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(10),()->{throw new AccessDeniedException("early fallback");})))
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1)))).isInstanceOf(AccessDeniedException.class);
            });
        });
    }
    @Test void mismatchedFinalizeFallbackFailureInvalidatesTheParentWitness() {
        allow();
        RequiredRecordReadGuard.withCanonicalRead("fixture_bundle",id(9),()->{
            guard.require("fixture_bundle",root(9,id(1)));RequiredRecordReadGuard.completePhysicalCanonicalRead("fixture_bundle",id(9));
            RequiredRecordReadGuard.finalizeCanonicalRead("fixture_bundle",id(9),()->{
                doThrow(new AccessDeniedException("revoked")).when(data).getById("fixture_member",id(1));
                assertThatThrownBy(()->RequiredRecordReadGuard.finalizeCanonicalRead("fixture_bundle",id(10),()->{
                    throw new AccessDeniedException("mismatched fallback");
                })).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1)))).isInstanceOf(AccessDeniedException.class);
            });
        });
    }
    @Test void dependencyPresentationScopeRestoresAfterNestedSuccessAndFailure() {
        allow();assertThat(RequiredRecordReadGuard.isResolvingReference()).isFalse();
        when(data.getById("fixture_member",id(1))).thenAnswer(call->{
            assertThat(RequiredRecordReadGuard.isResolvingReference()).isTrue();
            guard.require("fixture_bundle",root(10,id(2)));
            assertThat(RequiredRecordReadGuard.isResolvingReference()).isTrue();
            return Map.of("pid",id(1));
        });
        when(data.getById("fixture_member",id(2))).thenAnswer(call->{
            assertThat(RequiredRecordReadGuard.isResolvingReference()).isTrue();return Map.of("pid",id(2));
        });
        guard.require("fixture_bundle",root(9,id(1)));
        assertThat(RequiredRecordReadGuard.isResolvingReference()).isFalse();
        doThrow(new AccessDeniedException("revoked")).when(data).getById("fixture_member",id(2));
        assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1)))).isInstanceOf(AccessDeniedException.class);
        assertThat(RequiredRecordReadGuard.isResolvingReference()).isFalse();
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
    @Test void admittedDisplayIdentityKeepsDomainAndTenantWithoutScanningUnrelatedRoots() {
        allow();when(domains.buildDomainFilter("fixture_bundle",42L)).thenReturn("AND domain_id = 5");
        when(rows.selectByQuery(anyString(),anyMap())).thenAnswer(call->{
            String sql=call.getArgument(0);assertThat(sql).contains("tenant_id","domain_id = 5","created_by = 42");
            return sql.contains("AND pid IN ('"+id(9)+"')") ? List.of(root(9,id(1))) : java.util.Collections.nCopies(11,root(10,id(2)));
        });
        assertThat(guard.sqlFilter(7L,"fixture_bundle",42L,"AND created_by = 42 AND pid IN ('"+id(9)+"')")).isEqualTo("AND pid IN ('"+id(9)+"')");
        assertThatThrownBy(()->guard.sqlFilter(7L,"fixture_bundle",42L,"AND created_by = 42"))
                .isInstanceOf(MetaServiceException.class).hasMessageContaining("budget");
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
    @Test void incompleteLegacyRootsStayInvisibleWithoutPoisoningCompleteRoots() {
        allow();
        var missing=Map.<String,Object>of("pid",id(10),"tenant_id",7L);
        var malformed=Map.<String,Object>of("pid",id(11),"tenant_id",7L,"members","not-json");
        var duplicate=root(12,id(1),id(1));
        for(var row:List.of(missing,malformed,duplicate))
            assertThatThrownBy(()->guard.require("fixture_bundle",row)).isInstanceOf(AccessDeniedException.class);
        when(rows.selectByQuery(anyString(),anyMap())).thenReturn(List.of(missing,root(9,id(1)),malformed,duplicate));
        assertThat(guard.sqlFilter(7L,"fixture_bundle",42L,"")).isEqualTo("AND pid IN ('"+id(9)+"')");
    }
    @Test void invalidPolicyStillAbortsInsteadOfBecomingAnInvisibleRecord() {
        var model=RequiredReadReferencesTest.model(10); model.setExtension(Map.of(RequiredReadReferences.KEY,Map.of("version",99)));
        when(models.getModelDefinition("fixture_bundle")).thenReturn(Optional.of(model));
        assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1)))).isInstanceOf(MetaServiceException.class);
        assertThatThrownBy(()->guard.sqlFilter(7L,"fixture_bundle",42L,"")).isInstanceOf(MetaServiceException.class);
        verifyNoInteractions(rows,data);
    }
    @Test void oneRecordReferenceBudgetCannotBecomeAnOrdinaryInvisibleLegacyRoot() {
        var model=RequiredReadReferencesTest.model(10);
        var fields=new java.util.ArrayList<>(model.getFields());
        var refs=new java.util.ArrayList<Map<String,Object>>();
        var record=new java.util.LinkedHashMap<String,Object>(); record.put("pid",id(9999));record.put("tenant_id",7L);
        for(int index=0;index<17;index++) {
            String field="refs_"+index;
            fields.add(com.auraboot.framework.meta.dto.FieldDefinition.builder().code(field).columnName(field).dataType("json").build());
            refs.add(Map.of("field",field,"model","fixture_member","many",true,"allowEmpty",false));
            record.put(field,java.util.stream.IntStream.range(index*256+1,index*256+257).mapToObj(RequiredRecordReadGuardTest::id).toList());
        }
        model.setFields(fields);model.setExtension(Map.of(RequiredReadReferences.KEY,Map.of("version",1,"references",refs,"maxCandidateRecords",10,"minimumReferences",1)));
        when(models.getModelDefinition("fixture_bundle")).thenReturn(Optional.of(model));
        when(rows.selectByQuery(anyString(),anyMap())).thenReturn(List.of(record));
        assertThatThrownBy(()->guard.sqlFilter(7L,"fixture_bundle",42L,"")).isInstanceOf(MetaServiceException.class).hasMessageContaining("budget");
        verifyNoInteractions(data);
    }
    @Test void v2MutableOwnerRetainsEveryCurrentPermissionCheckAndNeverCarriesTerminalProofAcrossReads() {
        when(models.getModelDefinition("fixture_bundle")).thenReturn(Optional.of(RequiredReadReferencesTest.mutableDocument()));
        allow();guard.require("fixture_bundle",root(9,id(1)));
        doThrow(new AccessDeniedException("revoked")).when(data).getById("fixture_member",id(1));
        assertThatThrownBy(()->guard.require("fixture_bundle",root(9,id(1)))).isInstanceOf(AccessDeniedException.class);
        verify(data,times(2)).getById("fixture_member",id(1));
    }
}
