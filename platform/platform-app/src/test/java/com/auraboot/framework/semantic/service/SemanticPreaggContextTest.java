package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.semantic.dto.SemanticQueryResponse;
import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.auraboot.framework.semantic.entity.AbSemanticPreagg;
import com.auraboot.framework.semantic.exception.SemanticValidationException;
import com.auraboot.framework.semantic.mapper.AbSemanticModelMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticPreaggMapper;
import com.auraboot.framework.userattribute.service.UserAttributeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import org.springframework.security.access.AccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Hermetic characterization of thread identity; real persistence is covered by SemanticPreaggIT. */
class SemanticPreaggContextTest {
    private AbSemanticPreaggMapper mapper;
    private SemanticQueryService queries;
    private SemanticPreaggService service;
    private AbSemanticPreagg preagg;
    private MetaContext.Snapshot caller;
    private PlatformTransactionManager transactions;
    private TransactionStatus transaction;
    private JdbcTemplate jdbc;
    private TenantMemberService members;

    @BeforeEach
    void setup() {
        mapper = mock(AbSemanticPreaggMapper.class);
        AbSemanticModelMapper models = mock(AbSemanticModelMapper.class);
        queries = mock(SemanticQueryService.class);
        UserAttributeService attributes = mock(UserAttributeService.class);
        jdbc = mock(JdbcTemplate.class);
        transactions = mock(PlatformTransactionManager.class);
        transaction = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(transaction);
        members = mock(TenantMemberService.class);
        var member = new TenantMember(); member.setId(12L); member.setTenantId(1L);
        member.setUserId(2L); member.setStatus("active");
        when(members.findByTenantIdAndUserId(1L, 2L)).thenReturn(member);
        service = new SemanticPreaggService(mapper, models, queries, attributes, jdbc, new ObjectMapper(), transactions, members);
        AbSemanticModel model = new AbSemanticModel();
        model.setCode("orders");
        when(models.findByPid(1L, "model")).thenReturn(model);
        when(attributes.getAttributes(1L, 2L)).thenReturn(Map.of());
        SemanticQueryResponse explained = new SemanticQueryResponse();
        explained.setSql("SELECT 1 AS count");
        explained.setParams(List.of());
        when(queries.explainQuery(any(), any())).thenReturn(explained);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        preagg = new AbSemanticPreagg();
        preagg.setPid("preagg");
        preagg.setTenantId(1L);
        preagg.setCreatedBy(2L);
        preagg.setSemanticModelPid("model");
        preagg.setMetricCode("count");
        preagg.setDimensionCodes("[]");
        preagg.setMvName("mv_preagg");
        preagg.setRefreshMinutes(1);
        preagg.setUpdatedAt(OffsetDateTime.now().minusMinutes(2));
        when(mapper.findByPid(1L, "preagg")).thenReturn(preagg);
        when(mapper.listAllAcrossTenants()).thenReturn(List.of(preagg));
        MetaContext.setContext(1L, 2L, "caller-pid", "caller", Set.of(8L));
        MetaContext.setMemberId(3L);
        MetaContext.setEnvironmentId(4L);
        MetaContext.setOtelTraceId("caller-trace");
        caller = MetaContext.snapshot();
    }

    @Test
    void refreshBindsCreatorMembershipBeforeGovernedQuery() {
        when(queries.explainQuery(any(), any())).thenAnswer(invocation -> {
            assertThat(MetaContext.getCurrentMemberId()).isEqualTo(12L);
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(1L);
            assertThat(MetaContext.getCurrentUserId()).isEqualTo(2L);
            var explained = new SemanticQueryResponse(); explained.setSql("SELECT 1 AS count");
            explained.setParams(List.of()); return explained;
        });
        assertThat(service.refreshNow("preagg")).isEqualTo(1L);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void revokedCreatorDeniesRefreshBeforeDdlAndRestoresCaller() {
        when(members.findByTenantIdAndUserId(1L, 2L)).thenReturn(null);
        assertThatThrownBy(() -> service.refreshNow("preagg")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(queries, jdbc);
        verify(transactions).rollback(transaction);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void createRollsBackWhenMetadataInsertFailsAfterViewCreation() {
        doThrow(new IllegalStateException("insert failure")).when(mapper).insert(any(AbSemanticPreagg.class));
        assertThatThrownBy(() -> service.create("Orders", "model", "count", List.of(), 60))
                .hasMessage("insert failure");
        verify(jdbc).execute(startsWith("CREATE MATERIALIZED VIEW "));
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void refreshRollsBackWhenMetadataUpdateFailsAfterViewRebuild() {
        doThrow(new IllegalStateException("update failure")).when(mapper).updateById(any(AbSemanticPreagg.class));
        assertThatThrownBy(() -> service.refreshNow("preagg")).hasMessage("update failure");
        verify(jdbc).execute("DROP MATERIALIZED VIEW IF EXISTS mv_preagg");
        verify(jdbc).execute(startsWith("CREATE MATERIALIZED VIEW mv_preagg "));
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void deleteRollsBackMetadataWhenViewDropFails() {
        doThrow(new IllegalStateException("drop failure")).when(jdbc)
                .execute("DROP MATERIALIZED VIEW IF EXISTS mv_preagg");
        assertThatThrownBy(() -> service.delete("preagg")).hasMessage("drop failure");
        verify(mapper).softDelete(eq(1L), eq("preagg"), any(OffsetDateTime.class));
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
    }

    @Test
    void createCommitsViewAndMetadataInOneTransaction() {
        service.create("Orders", "model", "count", List.of(), 60);
        var order = inOrder(transactions, jdbc, mapper);
        order.verify(transactions).getTransaction(any());
        order.verify(jdbc).execute(startsWith("DROP MATERIALIZED VIEW IF EXISTS "));
        order.verify(jdbc).execute(startsWith("CREATE MATERIALIZED VIEW "));
        order.verify(mapper).insert(any(AbSemanticPreagg.class));
        order.verify(transactions).commit(transaction);
        verify(transactions, times(1)).getTransaction(any());
        verify(transactions, never()).rollback(any());
    }

    @AfterEach
    void clearContext() { MetaContext.clear(); }

    @Test
    void createRestoresCompleteCallerIdentity() {
        service.create("Orders", "model", "count", List.of(), 60);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
        verify(mapper).insert(any(AbSemanticPreagg.class));
    }

    @Test
    void refreshRestoresCompleteCallerIdentity() {
        assertThat(service.refreshNow("preagg")).isEqualTo(1L);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void failedRefreshRestoresCompleteCallerIdentity() {
        when(queries.explainQuery(any(), any())).thenThrow(new IllegalStateException("compile failure"));
        assertThatThrownBy(() -> service.refreshNow("preagg"))
                .isInstanceOf(IllegalStateException.class).hasMessage("compile failure");
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void schedulerRestoresCallerAndLeavesAnUnboundThreadUnbound() {
        service.refreshAllDue();
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
        preagg.setLastRefreshedAt(OffsetDateTime.now().minusMinutes(2));
        MetaContext.clear();
        service.refreshAllDue();
        assertThat(MetaContext.snapshot()).isNull();
    }

    @Test
    void corruptDimensionsFailBeforeSqlInsteadOfChangingTheQuery() {
        preagg.setDimensionCodes("{corrupt");
        assertThatThrownBy(() -> service.refreshNow("preagg"))
                .isInstanceOf(SemanticValidationException.class)
                .extracting("errorCode").isEqualTo("SEMANTIC_PREAGG_INVALID");
        verifyNoInteractions(queries);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }
}
