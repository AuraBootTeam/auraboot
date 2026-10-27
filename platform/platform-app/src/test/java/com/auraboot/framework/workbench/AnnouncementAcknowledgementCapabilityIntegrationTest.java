package com.auraboot.framework.workbench;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.extension.AnnouncementAcknowledgementAccessor;
import com.auraboot.framework.plugin.extension.AnnouncementAcknowledgementAccessor.*;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;
import com.auraboot.framework.workbench.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
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

/** Real PG service/transaction integration; no browser or application workflow claim. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}",
        "spring.datasource.username=${TEST_DATABASE_USERNAME}",
        "spring.datasource.password=${TEST_DATABASE_PASSWORD}",
        "aura.notification.plugin-delivery.enabled=true", "auraboot.announcement.worker-enabled=false"})
@ActiveProfiles("integration-test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnnouncementAcknowledgementCapabilityIntegrationTest extends BaseIntegrationTest {
    @Autowired private AnnouncementAcknowledgementAccessor capability;
    @Autowired private AnnouncementLifecycleService lifecycle;
    @Autowired private AnnouncementReleaseService releases;
    @Autowired private AnnouncementReaderService reader;
    @Autowired private AnnouncementLifecycleMapper roots;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private TenantMemberService memberships;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.auraboot.framework.plugin.extension.CommandMembershipLockAccessor identityLocks;

    private record Fixture(String root, String version, String hash) {}

    @BeforeEach void ownedRuntimeAndPermissions() {
        assertEquals("maritime-parallel-core", System.getenv("AURA_RUNTIME_NAME"));
        assertEquals("auraboot_26", System.getenv("POSTGRES_DB"));
        assertEquals("auraboot_26", jdbc.queryForObject("SELECT current_database()", String.class));
        for (String action : List.of("edit", "delete", "withdraw", "publish", "publish_all", "exempt")) {
            grantCommittedPermissionToTestRole("announcement.announcement." + action,
                    "announcement", "announcement:announcement", action, "Announcement " + action);
        }
        identity();
    }

    private void identity() {
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
        MetaContext.setMemberId(getTestTenantMember().getId());
    }

    private Fixture published() {
        identity(); String operation = UniqueIdGenerator.generate();
        var policy = Map.of("audience", Map.of("kind", "all"), "exemptionReason", "Synthetic command-gate integration", "acknowledgementRequired", true);
        var draft = lifecycle.execute("announcement:create_announcement", null,
                Map.of("title", "Command-gate verification " + operation, "content", "Synthetic regulation acknowledgement fixture", "policy", policy), operation, null);
        String root = (String) draft.get("pid");
        var release = releases.execute("announcement:publish", root, Map.of(), operation + "-publish", 1);
        new AnnouncementReleaseWorker(releases).tick(); identity();
        String version = (String) release.get("versionPid");
        String hash = (String) reader.detail(version).get("snapshotHash");
        return new Fixture(root, version, hash);
    }

    private Fixture acknowledged() {
        var fixture = published();
        reader.mark(fixture.version, Map.of("snapshotHash", fixture.hash), false);
        reader.mark(fixture.version, Map.of("snapshotHash", fixture.hash), true);
        return fixture;
    }

    private List<Requirement> required(Fixture fixture) {
        return List.of(new Requirement(fixture.version, fixture.hash, getTestTenantMember().getPid()));
    }

    private Check check(Fixture fixture) {
        return new TransactionTemplate(transactions).execute(status -> capability.checkForCommand(required(fixture)).getFirst());
    }

    private void withdraw(Fixture fixture) {
        int version = roots.readTarget(getTestTenant().getId(), fixture.root).getRowVersion();
        lifecycle.execute("announcement:archive", fixture.root, Map.of(), UniqueIdGenerator.generate(), version);
    }

    @Test void onlyOfficialExactVersionAcknowledgementSatisfiesTheGate() {
        var fixture = published();
        assertEquals(Status.NOT_ACKNOWLEDGED, check(fixture).status());
        reader.mark(fixture.version, Map.of("snapshotHash", fixture.hash), false);
        assertEquals(Status.NOT_ACKNOWLEDGED, check(fixture).status());
        var receipt = reader.mark(fixture.version, Map.of("snapshotHash", fixture.hash), true);
        var proof = check(fixture);
        assertEquals(Status.SATISFIED, proof.status()); assertEquals(fixture.hash, proof.acknowledgedHash());
        assertEquals(receipt.get("acknowledged_at"), proof.acknowledgedAt().toString());
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> capability.checkForCommand(required(fixture)));
    }

    @Test void aWithdrawalInsideTheBusinessTransactionInvalidatesAndRollsBackItsEarlierProof() {
        var fixture = acknowledged();
        int before = roots.readTarget(getTestTenant().getId(), fixture.root).getRowVersion();
        assertThrows(BusinessException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertEquals(Status.SATISFIED, capability.checkForCommand(required(fixture)).getFirst().status());
            withdraw(fixture);
            assertEquals("withdrawn", roots.readTarget(getTestTenant().getId(), fixture.root).getStatus());
        }));
        var retained = roots.readTarget(getTestTenant().getId(), fixture.root);
        assertEquals("published", retained.getStatus()); assertEquals(before, retained.getRowVersion());
        assertEquals(Status.SATISFIED, check(fixture).status());
    }

    @Test void anActualExpiryChangeInsideTheTransactionCannotCommitAnEarlierProof() {
        var fixture = acknowledged();
        assertThrows(BusinessException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertEquals(Status.SATISFIED, capability.checkForCommand(required(fixture)).getFirst().status());
            // Controlled test-only fault in this fixture's existing control row.
            assertEquals(1, jdbc.update("UPDATE ab_announcement_version_control SET expires_at=clock_timestamp()-interval '1 second' WHERE tenant_id=? AND version_pid=?",
                    getTestTenant().getId(), fixture.version));
        }));
        assertEquals(Status.SATISFIED, check(fixture).status());
    }

    @Test void sealedAcknowledgementDoesNotTransferToANewMemberIncarnation() {
        var fixture = acknowledged();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                status.setRollbackOnly();
                // Reproduce member departure/re-entry without changing other fixtures.
                assertEquals(1, jdbc.update("UPDATE ab_tenant_member SET deleted_flag=TRUE,status='inactive' WHERE id=? AND tenant_id=?",
                        getTestTenantMember().getId(), getTestTenant().getId()));
                var replacement = memberships.addMember(getTestUser().getId(), getTestTenant().getId(), "active");
                assertNotEquals(getTestTenantMember().getPid(), replacement.getPid());
                MetaContext.setMemberId(replacement.getId());
                assertEquals(Status.MEMBER_CHANGED, capability.checkForCommand(required(fixture)).getFirst().status());
                assertEquals(Status.NOT_VISIBLE, capability.checkForCommand(List.of(
                        new Requirement(fixture.version, fixture.hash, replacement.getPid()))).getFirst().status());
            });
        } finally { identity(); }
        assertEquals(Status.SATISFIED, check(fixture).status());
    }

    @Test void anExistingIdentityBatchIsReusedBeforeAnnouncementRoots() {
        var fixture = acknowledged();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var actor = new com.auraboot.framework.plugin.extension.CommandMembershipLockAccessor.MembershipAssertion(
                    getTestUser().getPid(), getTestTenantMember().getPid());
            assertEquals(getTestTenantMember().getPid(), identityLocks.requireEnabledMemberships(List.of(actor)).getFirst().memberPid());
            assertEquals(Status.SATISFIED, capability.checkForCommand(required(fixture)).getFirst().status());
            assertEquals(getTestTenantMember().getPid(), identityLocks.requireEnabledMemberships(List.of(actor)).getFirst().memberPid());
        });
    }

    @Test void aConcurrentWithdrawalWaitsForTheProofTransactionThenInvalidatesFutureChecks() throws Exception {
        var fixture = acknowledged();
        var started = new CountDownLatch(1); var backend = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<?>[] future = new java.util.concurrent.Future<?>[1];
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertEquals(Status.SATISFIED, capability.checkForCommand(required(fixture)).getFirst().status());
                future[0] = executor.submit(() -> {
                    identity();
                    try {
                        new TransactionTemplate(transactions).executeWithoutResult(other -> {
                            backend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); started.countDown();
                            withdraw(fixture);
                        });
                    } finally { MetaContext.clear(); }
                });
                try { assertTrue(started.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                await().atMost(Duration.ofSeconds(5)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT cardinality(pg_blocking_pids(?))>0", Boolean.class, backend.get())));
                assertFalse(future[0].isDone(), "Actual database blocker must remain until the caller commits");
            });
            future[0].get(10, TimeUnit.SECONDS);
            assertEquals(Status.WITHDRAWN, check(fixture).status());
        } finally {
            executor.shutdown(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); identity();
        }
    }

    @Test void everyRequiredVersionInABatchMustRemainSatisfiedThroughCommit() {
        var first = acknowledged(); var second = acknowledged();
        var requirements = List.of(required(second).getFirst(), required(first).getFirst());
        var checks = new TransactionTemplate(transactions).execute(status -> capability.checkForCommand(requirements));
        assertEquals(List.of(second.version, first.version), checks.stream().map(Check::requestedVersionPid).toList());
        assertTrue(checks.stream().allMatch(c -> c.status() == Status.SATISFIED));
        assertThrows(BusinessException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertTrue(capability.checkForCommand(requirements).stream().allMatch(c -> c.status() == Status.SATISFIED));
            withdraw(first);
        }));
        assertEquals(Status.SATISFIED, check(first).status()); assertEquals(Status.SATISFIED, check(second).status());
    }
}
