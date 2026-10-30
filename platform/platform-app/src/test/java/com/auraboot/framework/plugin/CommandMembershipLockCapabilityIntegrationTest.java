package com.auraboot.framework.plugin;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.extension.CommandMembershipLockAccessor;
import com.auraboot.framework.plugin.extension.CommandMembershipLockAccessor.MembershipAssertion;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Real Core/PG service transactions; not an HTTP, native command authorization or browser claim. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}", "spring.datasource.username=${TEST_DATABASE_USERNAME}",
        "spring.datasource.password=${TEST_DATABASE_PASSWORD}", "auraboot.announcement.worker-enabled=false"})
@ActiveProfiles("integration-test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommandMembershipLockCapabilityIntegrationTest extends BaseIntegrationTest {
    @Autowired private CommandMembershipLockAccessor locks;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private TenantMemberService memberships;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;

    private record Person(User user, TenantMember member) {
        MembershipAssertion assertion() { return new MembershipAssertion(user.getPid(), member.getPid()); }
    }
    @BeforeEach void ownedAllocation() {
        assertEquals("maritime-parallel-core", System.getenv("AURA_RUNTIME_NAME"));
        assertEquals("auraboot_26", System.getenv("POSTGRES_DB"));
        assertEquals("auraboot_26", jdbc.queryForObject("SELECT current_database()", String.class));
        baseIdentity();
    }
    @AfterEach void restoreContext() { baseIdentity(); }
    private void baseIdentity() {
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
        MetaContext.setMemberId(getTestTenantMember().getId());
    }
    private Person base() { return new Person(getTestUser(), getTestTenantMember()); }
    private void identity(Person person) {
        MetaContext.setContext(getTestTenant().getId(), person.user.getId(), person.user.getPid(), person.user.getUserName());
        MetaContext.setMemberId(person.member.getId());
    }
    private Person person() {
        var user = users.signUp("membership-lock-" + UniqueIdGenerator.generate() + "@integration.invalid",
                "Synthetic-lock-fixture-42!", "Synthetic membership lock participant");
        var member = memberships.addMember(user.getId(), getTestTenant().getId(), "active");
        return new Person(user, member);
    }
    private void disable(Person person) {
        var current = users.findByPid(person.user.getPid()); current.setEnabled(false); users.update(current);
    }
    private List<Map<String,Object>> userState(Person person) {
        return jdbc.queryForList("SELECT id,pid,is_enabled,deleted_flag,updated_at FROM ab_user WHERE id=?", person.user.getId());
    }
    private List<Map<String,Object>> memberState(Person person) {
        return jdbc.queryForList("SELECT id,pid,status,leave_date,deleted_flag,updated_at FROM ab_tenant_member WHERE id=?", person.member.getId());
    }
    private void lock(List<MembershipAssertion> expected) {
        assertEquals(expected.stream().map(MembershipAssertion::memberPid).toList(),
                locks.requireEnabledMemberships(expected).stream().map(i -> i.memberPid()).toList());
    }

    @Test void exactBatchAndSubsetHaveNoIdentityFieldOrVersionSideEffects() {
        var other = person(); var beforeUser = userState(other); var beforeMember = memberState(other);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            lock(List.of(other.assertion(), base().assertion())); lock(List.of(base().assertion()));
        });
        assertEquals(beforeUser, userState(other)); assertEquals(beforeMember, memberState(other));
    }

    @Test void aMandatoryProxyRejectsNoTransactionAndAForeignDatasourceTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> lock(List.of(base().assertion())));
        var foreign = new org.postgresql.ds.PGSimpleDataSource();
        foreign.setURL(System.getenv("TEST_DATABASE_URL"));
        foreign.setUser(System.getenv("TEST_DATABASE_USERNAME")); foreign.setPassword(System.getenv("TEST_DATABASE_PASSWORD"));
        new TransactionTemplate(new DataSourceTransactionManager(foreign)).executeWithoutResult(tx ->
                assertThrows(IllegalTransactionStateException.class, () -> lock(List.of(base().assertion()))));
    }

    @Test void globalDisableOfTheAuthenticatedActorWaitsForItsCommandTransaction() throws Exception {
        var actor = person(); identity(actor);
        race(List.of(actor.assertion()), () -> disable(actor));
        assertFalse(users.findByPid(actor.user.getPid()).isEnabled());
        assertThrows(AccessDeniedException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> lock(List.of(actor.assertion()))));
    }

    @Test void globalDisableOfARecipientWaitsForTheCompleteIdentityBatch() throws Exception {
        var recipient = person();
        race(List.of(base().assertion(), recipient.assertion()), () -> disable(recipient));
        assertFalse(users.findByPid(recipient.user.getPid()).isEnabled());
        assertThrows(AccessDeniedException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(tx ->
                lock(List.of(base().assertion(), recipient.assertion()))));
    }

    @Test void memberOffboardingWaitsForTheSamePhysicalTransaction() throws Exception {
        var recipient = person();
        race(List.of(base().assertion(), recipient.assertion()), () -> assertTrue(memberships.deactivateMember(recipient.member.getId())));
        assertThrows(AccessDeniedException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(tx ->
                lock(List.of(base().assertion(), recipient.assertion()))));
    }

    @Test void removedAndReaddedMembershipNeverReplacesAnAssertedOldPid() throws Exception {
        var recipient = person();
        race(List.of(base().assertion(), recipient.assertion()), () -> {
            assertTrue(memberships.removeMember(recipient.member.getId()));
            memberships.addMember(recipient.user.getId(), getTestTenant().getId(), "active");
        });
        var replacement = memberships.findByTenantIdAndUserId(getTestTenant().getId(), recipient.user.getId());
        assertNotEquals(recipient.member.getPid(), replacement.getPid());
        assertThrows(AccessDeniedException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(tx ->
                lock(List.of(base().assertion(), recipient.assertion()))));
        // Identity eligibility is not a vessel grant; only the newly asserted identity can be locked.
        new TransactionTemplate(transactions).executeWithoutResult(tx -> lock(List.of(base().assertion(),
                new MembershipAssertion(recipient.user.getPid(), replacement.getPid()))));
    }

    @ParameterizedTest @ValueSource(strings = {"disable-actor", "disable-recipient", "leave", "leave-date", "delete-rejoin"})
    void ownTransactionIdentityChangesCannotCommitAnEarlierProof(String fault) {
        var actor = person(); var recipient = person(); identity(actor);
        var actorBefore = userState(actor); var userBefore = userState(recipient); var memberBefore = memberState(recipient);
        assertThrows(AccessDeniedException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            lock(List.of(actor.assertion(), recipient.assertion()));
            switch (fault) {
                case "disable-actor" -> disable(actor);
                case "disable-recipient" -> disable(recipient);
                case "leave" -> assertTrue(memberships.deactivateMember(recipient.member.getId()));
                case "leave-date" -> assertEquals(1, jdbc.update("UPDATE ab_tenant_member SET leave_date=CURRENT_DATE WHERE id=? AND tenant_id=?",
                        recipient.member.getId(), getTestTenant().getId()));
                case "delete-rejoin" -> {
                    assertTrue(memberships.removeMember(recipient.member.getId()));
                    memberships.addMember(recipient.user.getId(), getTestTenant().getId(), "active");
                }
            }
        }));
        assertEquals(actorBefore, userState(actor)); assertEquals(userBefore, userState(recipient));
        assertEquals(memberBefore, memberState(recipient));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> lock(List.of(actor.assertion(), recipient.assertion())));
    }

    @Test void lateBatchExpansionAndForeignTenantCannotAcquireAValidProof() {
        var other = person();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            lock(List.of(base().assertion())); lock(List.of(base().assertion(), other.assertion()));
        }));
        try {
            MetaContext.setContext(getTestTenant().getId() + 1_000_000L, getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
            MetaContext.setMemberId(getTestTenantMember().getId());
            assertThrows(AccessDeniedException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> lock(List.of(base().assertion()))));
        } finally { baseIdentity(); }
    }

    @Test void requiresNewUsesItsOwnProofAndResumesTheOriginalTransactionProof() {
        var other = person();
        var inner = new TransactionTemplate(transactions); inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            lock(List.of(base().assertion()));
            try {
                identity(other); inner.executeWithoutResult(next -> lock(List.of(other.assertion())));
            } finally { baseIdentity(); }
            lock(List.of(base().assertion()));
        });
    }

    private void race(List<MembershipAssertion> expected, Runnable mutation) throws Exception {
        var started = new CountDownLatch(1); var backend = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor(); java.util.concurrent.Future<?>[] future = new java.util.concurrent.Future<?>[1];
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                lock(expected);
                future[0] = executor.submit(() -> {
                    baseIdentity();
                    try {
                        new TransactionTemplate(transactions).executeWithoutResult(other -> {
                            jdbc.execute("SET LOCAL lock_timeout='8s'");
                            backend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); started.countDown(); mutation.run();
                        });
                    } finally { MetaContext.clear(); }
                });
                try { assertTrue(started.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                await().atMost(Duration.ofSeconds(5)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT cardinality(pg_blocking_pids(?))>0", Boolean.class, backend.get())));
                assertFalse(future[0].isDone(), "The competing write must actually be blocked in PostgreSQL");
            });
            future[0].get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdown(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
