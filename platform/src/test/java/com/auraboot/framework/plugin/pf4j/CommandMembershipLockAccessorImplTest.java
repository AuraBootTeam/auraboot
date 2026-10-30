package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.CommandMembershipLockAccessor.MembershipAssertion;
import com.auraboot.framework.plugin.mapper.CommandMembershipLockMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic contract/decision checks; actual lock retention and isolation are separate real-PG tests. */
class CommandMembershipLockAccessorImplTest {
    private CommandMembershipLockMapper mapper;
    private CommandMembershipLockAccessorImpl service;
    private DataSource source;
    private Connection connection;
    private Map<String,Object> actor;
    private Map<String,Object> recipient;
    private final MembershipAssertion a = new MembershipAssertion("actor", "member-a");
    private final MembershipAssertion r = new MembershipAssertion("recipient", "member-r");

    @BeforeEach void prepare() throws Exception {
        MetaContext.setContext(7L, 20L, "actor", "Actor"); MetaContext.setMemberId(30L);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        source = mock(DataSource.class); connection = mock(Connection.class);
        var holder = new ConnectionHolder(connection); holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source, holder);
        var sessions = mock(SqlSessionFactory.class); var configuration = new Configuration();
        configuration.setEnvironment(new Environment("unit", mock(org.apache.ibatis.transaction.TransactionFactory.class), source));
        when(sessions.getConfiguration()).thenReturn(configuration);
        mapper = mock(CommandMembershipLockMapper.class); service = new CommandMembershipLockAccessorImpl(mapper, sessions);
        actor = row("actor", "member-a", 20L, 30L); recipient = row("recipient", "member-r", 10L, 40L);
        when(mapper.exact(7L, "actor", "member-a")).thenAnswer(call -> actor);
        when(mapper.exact(7L, "recipient", "member-r")).thenAnswer(call -> recipient);
        when(mapper.lockUser(anyLong())).thenAnswer(call -> call.getArgument(0));
        when(mapper.lockMember(eq(7L), anyLong())).thenAnswer(call -> call.getArgument(1));
    }
    @AfterEach void clear() {
        if (TransactionSynchronizationManager.hasResource(source)) TransactionSynchronizationManager.unbindResource(source);
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false); MetaContext.clear();
    }
    private static Map<String,Object> row(String user, String member, Long uid, Long mid) {
        return new HashMap<>(Map.of("user_id", uid, "user_pid", user, "user_name", user,
                "display_name", user, "is_enabled", true, "user_deleted", false,
                "member_id", mid, "member_pid", member, "status", "active", "member_deleted", false));
    }
    private void commitCheck() {
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.beforeCommit(false));
    }
    @Test void locksAllUsersThenAllMembersInNumericOrderAndKeepsRequestOrder() {
        var result = service.requireEnabledMemberships(List.of(a, r));
        assertEquals(List.of("actor", "recipient"), result.stream().map(i -> i.user().pid()).toList());
        var order = inOrder(mapper);
        order.verify(mapper).exact(7L, "actor", "member-a"); order.verify(mapper).exact(7L, "recipient", "member-r");
        order.verify(mapper).lockUser(10L); order.verify(mapper).lockUser(20L);
        order.verify(mapper).lockMember(7L, 30L); order.verify(mapper).lockMember(7L, 40L);
        assertThrows(UnsupportedOperationException.class, () -> result.clear()); commitCheck();
    }
    @ParameterizedTest @ValueSource(strings={"no-tx", "read-only", "no-sync", "foreign-resource", "not-synchronized", "autocommit", "physical-read-only", "closed"})
    void refusesUnrelatedOrNonWritablePhysicalTransactions(String fault) throws Exception {
        switch (fault) {
            case "no-tx" -> TransactionSynchronizationManager.setActualTransactionActive(false);
            case "read-only" -> TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            case "no-sync" -> TransactionSynchronizationManager.clearSynchronization();
            case "foreign-resource" -> TransactionSynchronizationManager.unbindResource(source);
            case "not-synchronized" -> ((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).setSynchronizedWithTransaction(false);
            case "autocommit" -> when(connection.getAutoCommit()).thenReturn(true);
            case "physical-read-only" -> when(connection.isReadOnly()).thenReturn(true);
            case "closed" -> when(connection.isClosed()).thenReturn(true);
        }
        assertThrows(IllegalStateException.class, () -> service.requireEnabledMemberships(List.of(a)));
        verifyNoInteractions(mapper);
    }
    @Test void requiresTrustedExactActorAndDistinctCompleteAssertions() {
        assertThrows(AccessDeniedException.class, () -> service.requireEnabledMemberships(List.of(r)));
        assertThrows(IllegalArgumentException.class, () -> service.requireEnabledMemberships(List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.requireEnabledMemberships(List.of(a, a)));
        MetaContext.setMemberId(999L);
        assertThrows(AccessDeniedException.class, () -> service.requireEnabledMemberships(List.of(a)));
        MetaContext.clear(); assertThrows(AccessDeniedException.class, () -> service.requireEnabledMemberships(List.of(a)));
    }
    @ParameterizedTest @ValueSource(strings={"disabled", "user-deleted", "member-deleted", "inactive", "leave-date", "user-id", "member-id", "member-pid"})
    void neverCommitsAfterAnIdentityIsChangedInsideTheCallersOwnTransaction(String fault) {
        service.requireEnabledMemberships(List.of(a, r));
        switch (fault) {
            case "disabled" -> recipient.put("is_enabled", false);
            case "user-deleted" -> recipient.put("user_deleted", true);
            case "member-deleted" -> recipient.put("member_deleted", true);
            case "inactive" -> recipient.put("status", "inactive");
            case "leave-date" -> recipient.put("leave_date", "2026-10-27");
            case "user-id" -> recipient.put("user_id", 999L);
            case "member-id" -> recipient.put("member_id", 999L);
            case "member-pid" -> recipient.put("member_pid", "replacement");
        }
        assertThrows(AccessDeniedException.class, this::commitCheck);
    }
    @Test void subsetReuseDoesNotGrowOrInvertTheTransactionLockSet() {
        service.requireEnabledMemberships(List.of(a, r));
        assertEquals("actor", service.requireEnabledMemberships(List.of(a)).getFirst().user().pid());
        verify(mapper, times(1)).lockUser(20L); verify(mapper, times(1)).lockMember(7L, 30L);
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        assertThrows(IllegalStateException.class, () -> service.requireEnabledMemberships(
                List.of(a, new MembershipAssertion("later", "member-later"))));
    }
    @Test void anIdentityRaceBeforeTheLockIsRecheckedAfterAllLocks() {
        when(mapper.lockUser(10L)).thenAnswer(call -> { recipient.put("is_enabled", false); return 10L; });
        assertThrows(AccessDeniedException.class, () -> service.requireEnabledMemberships(List.of(a, r)));
    }
    @Test void changingTheTrustedActorCannotReuseAnEarlierProof() {
        service.requireEnabledMemberships(List.of(a, r));
        MetaContext.setContext(7L, 10L, "recipient", "Recipient"); MetaContext.setMemberId(40L);
        assertThrows(AccessDeniedException.class, () -> service.requireEnabledMemberships(List.of(r)));
    }
}
