package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.CommandMembershipLockAccessor;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor.TenantUserIdentity;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor.UserIdentity;
import com.auraboot.framework.plugin.mapper.CommandMembershipLockMapper;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Locks identities without exposing Core SQL or changing identity fields in application plugins. */
@Service
@RequiredArgsConstructor
public class CommandMembershipLockAccessorImpl implements CommandMembershipLockAccessor {
    private final CommandMembershipLockMapper mapper;
    private final SqlSessionFactory sessions;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<TenantUserIdentity> requireEnabledMemberships(List<MembershipAssertion> expected) {
        Connection connection = coreConnection();
        Actor actor = actor();
        if (expected == null || expected.isEmpty() || expected.size() > 32)
            throw new IllegalArgumentException("One to thirty-two exact identity assertions are required");
        expected = List.copyOf(expected);
        var users = new HashSet<String>(); var members = new HashSet<String>();
        for (var item : expected) {
            if (!users.add(item.userPid()) || !members.add(item.memberPid()))
                throw new IllegalArgumentException("Identity assertions must name distinct users and members");
        }
        if (!users.contains(actor.userPid())) throw denied();
        var existing = TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(s -> s instanceof Proof p && p.owner == this).map(s -> (Proof) s).findFirst().orElse(null);
        if (existing != null) {
            if (existing.connection != connection || !existing.actor.equals(actor)) throw denied();
            // Growing a lock set after member locks would invert the shared user/member order.
            if (!existing.rows.keySet().containsAll(expected))
                throw new IllegalStateException("Declare the complete identity set before taking member locks");
            existing.verify();
            return expected.stream().map(item -> existing.rows.get(item).identity()).toList();
        }
        var rows = new LinkedHashMap<MembershipAssertion,Row>();
        for (var item : expected) rows.put(item, resolve(actor, item));
        rows.values().stream().map(Row::userId).distinct().sorted().forEach(id -> {
            if (!id.equals(mapper.lockUser(id))) throw denied();
        });
        rows.values().stream().map(Row::memberId).distinct().sorted().forEach(id -> {
            if (!id.equals(mapper.lockMember(actor.tenant(), id))) throw denied();
        });
        var proof = new Proof(actor, connection, rows);
        proof.verify();
        TransactionSynchronizationManager.registerSynchronization(proof);
        return rows.values().stream().map(Row::identity).toList();
    }

    private Connection coreConnection() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Identity locks require an existing writable Core transaction");
        var source = sessions.getConfiguration().getEnvironment().getDataSource();
        Object resource = TransactionSynchronizationManager.getResource(source);
        if (!(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction())
            throw new IllegalStateException("The physical Core datasource transaction is required");
        Connection connection = holder.getConnection();
        try {
            if (connection.getAutoCommit() || connection.isReadOnly() || connection.isClosed())
                throw new IllegalStateException("The physical Core transaction is not writable");
        } catch (SQLException error) { throw new IllegalStateException("Cannot validate the Core transaction", error); }
        return connection;
    }

    private record Actor(Long tenant, Long userId, String userPid, Long memberId) {}
    private static Actor actor() {
        if (!MetaContext.exists()) throw denied();
        var value = new Actor(MetaContext.getCurrentTenantId(), MetaContext.getCurrentUserId(),
                MetaContext.getCurrentUserPid(), MetaContext.getCurrentMemberId());
        if (value.tenant() == null || value.tenant() < 1 || value.userId() == null || value.userId() < 1
                || value.userPid() == null || value.userPid().isBlank() || value.memberId() == null || value.memberId() < 1)
            throw denied();
        return value;
    }

    private record Row(Long userId, Long memberId, TenantUserIdentity identity) {}
    private Row resolve(Actor actor, MembershipAssertion asserted) {
        Map<String,Object> row = mapper.exact(actor.tenant(), asserted.userPid(), asserted.memberPid());
        if (row == null || !asserted.userPid().equals(row.get("user_pid"))
                || !asserted.memberPid().equals(row.get("member_pid"))
                || !Boolean.TRUE.equals(row.get("is_enabled")) || !Boolean.FALSE.equals(row.get("user_deleted"))
                || !Boolean.FALSE.equals(row.get("member_deleted")) || !"active".equalsIgnoreCase((String) row.get("status"))
                || row.get("leave_date") != null) throw denied();
        Long userId = id(row.get("user_id")), memberId = id(row.get("member_id"));
        if (asserted.userPid().equals(actor.userPid())
                && (!userId.equals(actor.userId()) || !memberId.equals(actor.memberId()))) throw denied();
        return new Row(userId, memberId, new TenantUserIdentity(new UserIdentity(userId, asserted.userPid(),
                (String) row.get("user_name"), (String) row.get("display_name")), asserted.memberPid(), memberId));
    }

    private final class Proof implements TransactionSynchronization {
        private final CommandMembershipLockAccessorImpl owner = CommandMembershipLockAccessorImpl.this;
        private final Actor actor;
        private final Connection connection;
        private final Map<MembershipAssertion,Row> rows;
        private Proof(Actor actor, Connection connection, Map<MembershipAssertion,Row> rows) {
            this.actor = actor; this.connection = connection; this.rows = Map.copyOf(rows);
        }
        private void verify() {
            if (coreConnection() != connection) throw new IllegalStateException("The Core transaction changed");
            rows.forEach((assertion, before) -> {
                var current = resolve(actor, assertion);
                if (!Objects.equals(before.userId(), current.userId()) || !Objects.equals(before.memberId(), current.memberId()))
                    throw denied();
            });
        }
        @Override public void beforeCommit(boolean readOnly) { verify(); }
    }

    private static Long id(Object value) {
        if (!(value instanceof Number number) || number.longValue() < 1) throw denied();
        return number.longValue();
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("The exact enabled tenant membership is required"); }
}
