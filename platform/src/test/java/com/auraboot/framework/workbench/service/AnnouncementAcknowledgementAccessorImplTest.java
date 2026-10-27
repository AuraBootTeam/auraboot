package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.AnnouncementAcknowledgementAccessor.*;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.AnnouncementAcknowledgementMapper;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic decision/transaction-precondition checks; database locking is tested separately. */
class AnnouncementAcknowledgementAccessorImplTest {
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private AnnouncementAcknowledgementMapper proof;
    private AnnouncementLifecycleMapper roots;
    private AnnouncementAcknowledgementAccessorImpl service;
    private Map<String,Object> version;
    private Map<String,Object> recipient;
    private Announcement root;

    @BeforeEach void context() {
        proof = mock(AnnouncementAcknowledgementMapper.class);
        roots = mock(AnnouncementLifecycleMapper.class);
        service = new AnnouncementAcknowledgementAccessorImpl(proof, roots, new AnnouncementCodec(new ObjectMapper()), mock(com.auraboot.framework.plugin.extension.CommandMembershipLockAccessor.class));
        MetaContext.setContext(1L, 2L, "user-public", "recipient");
        MetaContext.setMemberId(3L);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.initSynchronization();
        when(proof.currentMember(1L, 2L, "user-public")).thenReturn(Map.of("member_id", 3L, "member_pid", "member-public", "user_pid", "user-public"));
        when(proof.databaseNow()).thenReturn(NOW);
        version = new HashMap<>(Map.of("version_pid", "version-public", "announcement_pid", "root-public",
                "snapshot_hash", HASH, "snapshot", "{\"policy\":{\"acknowledgementRequired\":true}}",
                "publication_state", "published", "expires_at", NOW.plusSeconds(60)));
        when(proof.version(1L, "version-public")).thenAnswer(call -> version);
        root = new Announcement(); root.setPid("root-public"); root.setStatus("published");
        root.setActiveVersionPid("version-public"); root.setDeletedFlag(false);
        when(roots.lock(1L, "root-public")).thenReturn(root);
        recipient = new HashMap<>(Map.of("first_read_at", NOW.minusSeconds(2), "acknowledged_at", NOW.minusSeconds(1), "acknowledged_hash", HASH));
        when(proof.recipient(1L, "version-public", "user-public", "member-public")).thenAnswer(call -> recipient);
    }

    @AfterEach void clear() {
        MetaContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private List<Requirement> required() { return List.of(new Requirement("version-public", HASH, "member-public")); }
    private Check check() { return service.checkForCommand(required()).getFirst(); }

    @Test void exactRecipientVersionAndHashProduceAnImmutableProof() {
        var result = service.checkForCommand(required()); var check = result.getFirst();
        assertEquals(Status.SATISFIED, check.status()); assertEquals("version-public", check.activeVersionPid());
        assertEquals(HASH, check.snapshotHash()); assertEquals(HASH, check.acknowledgedHash());
        assertEquals(NOW.minusSeconds(1), check.acknowledgedAt()); assertEquals(NOW, check.checkedAt());
        assertEquals(NOW.plusSeconds(60), check.validUntil());
        assertTrue(check.effective() && check.visible() && check.memberIncarnationMatch() && check.acknowledgementRequired());
        assertThrows(UnsupportedOperationException.class, () -> result.add(check));
        verify(proof, times(2)).version(1L, "version-public");
    }

    @ParameterizedTest @ValueSource(strings = {"no-transaction", "read-only", "no-sync"})
    void requiresAnExistingWritableSynchronizedTransaction(String failure) {
        if (failure.equals("no-transaction")) TransactionSynchronizationManager.setActualTransactionActive(false);
        if (failure.equals("read-only")) TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        if (failure.equals("no-sync")) TransactionSynchronizationManager.clearSynchronization();
        assertThrows(IllegalStateException.class, this::check); verifyNoInteractions(proof, roots);
    }

    @Test void missingAuthenticationDoesNotQueryAnyRecipient() {
        MetaContext.clear(); assertThrows(AccessDeniedException.class, this::check); verifyNoInteractions(proof, roots);
    }

    @ParameterizedTest @ValueSource(strings = {"inactive", "context-incarnation", "requested-incarnation"})
    void memberChangesCannotReuseAnOldAcknowledgement(String failure) {
        if (failure.equals("inactive")) when(proof.currentMember(anyLong(), anyLong(), anyString())).thenReturn(null);
        if (failure.equals("context-incarnation")) MetaContext.setMemberId(99L);
        var requirements = failure.equals("requested-incarnation") ? List.of(new Requirement("version-public", HASH, "old-member")) : required();
        var check = service.checkForCommand(requirements).getFirst();
        assertEquals(Status.MEMBER_CHANGED, check.status()); assertFalse(check.memberIncarnationMatch());
        verify(proof, never()).recipient(anyLong(), anyString(), anyString(), anyString());
    }

    @Test void missingVersionIsNotAcknowledged() {
        when(proof.version(anyLong(), anyString())).thenReturn(null);
        assertEquals(Status.MISSING, check().status()); verifyNoInteractions(roots);
    }

    @ParameterizedTest @ValueSource(strings = {"withdrawn", "expired", "superseded"})
    void lifecycleChangesInvalidateTheRequiredVersion(String state) {
        root.setActiveVersionPid(null);
        if (!state.equals("superseded")) version.put("publication_state", state);
        var check = check();
        assertEquals(Status.valueOf(state.toUpperCase(java.util.Locale.ROOT)), check.status());
        assertFalse(check.effective()); assertFalse(check.visible());
        verify(proof, never()).recipient(anyLong(), anyString(), anyString(), anyString());
    }

    @Test void databaseExpiryBoundaryIsExclusive() {
        version.put("expires_at", NOW); assertEquals(Status.EXPIRED, check().status());
    }

    @Test void nonRecipientsCannotAcquireProofByKnowingTheVersionPid() {
        when(proof.recipient(anyLong(), anyString(), anyString(), anyString())).thenReturn(null);
        var check = check(); assertEquals(Status.NOT_VISIBLE, check.status()); assertNull(check.acknowledgedHash());
    }

    @Test void aChangedConfiguredSnapshotDoesNotPass() {
        assertEquals(Status.SNAPSHOT_CHANGED, service.checkForCommand(List.of(new Requirement("version-public", "b".repeat(64), "member-public"))).getFirst().status());
    }

    @Test void anOrdinaryAnnouncementCannotSatisfyAnExplicitAcknowledgementGate() {
        version.put("snapshot", "{\"policy\":{\"acknowledgementRequired\":false}}");
        assertEquals(Status.NOT_REQUIRED, check().status());
    }

    @ParameterizedTest @ValueSource(strings = {"read-only", "no-ack", "wrong-hash", "future-ack"})
    void readingAndIncompleteOrDifferentAcknowledgementsDoNotPass(String state) {
        if (state.equals("read-only")) recipient.remove("first_read_at");
        if (state.equals("no-ack")) recipient.remove("acknowledged_at");
        if (state.equals("wrong-hash")) recipient.put("acknowledged_hash", "b".repeat(64));
        if (state.equals("future-ack")) recipient.put("acknowledged_at", NOW.plusSeconds(1));
        assertEquals(Status.NOT_ACKNOWLEDGED, check().status());
    }

    @Test void expiryIsCheckedAgainBeforeTheBusinessTransactionCommits() {
        when(proof.databaseNow()).thenReturn(NOW, NOW.plusSeconds(60));
        assertEquals(Status.SATISFIED, check().status());
        assertThrows(com.auraboot.framework.exception.BusinessException.class, () -> TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.beforeCommit(false)));
    }

    @Test void freshProofAllowsTheTransactionCommitCheck() {
        when(proof.databaseNow()).thenReturn(NOW, NOW.plusSeconds(59)); check();
        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.beforeCommit(false)));
    }

    @Test void sameTransactionWithdrawalCannotCommitUsingAnEarlierSatisfiedProof() {
        check(); root.setActiveVersionPid(null); version.put("publication_state", "withdrawn");
        assertThrows(com.auraboot.framework.exception.BusinessException.class,
                () -> TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.beforeCommit(false)));
    }

    @Test void sameTransactionMemberChangeCannotCommitUsingAnEarlierSatisfiedProof() {
        check(); when(proof.currentMember(1L, 2L, "user-public")).thenReturn(null);
        assertThrows(com.auraboot.framework.exception.BusinessException.class,
                () -> TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.beforeCommit(false)));
    }

    @Test void sameTransactionSnapshotChangeCannotCommitUsingAnEarlierSatisfiedProof() {
        check(); version.put("snapshot_hash", "b".repeat(64));
        assertThrows(com.auraboot.framework.exception.BusinessException.class,
                () -> TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.beforeCommit(false)));
    }

    @Test void duplicateEmptyAndOversizedPlansAreRejectedBeforeDataAccess() {
        assertThrows(IllegalArgumentException.class, () -> service.checkForCommand(List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.checkForCommand(List.of(required().getFirst(), required().getFirst())));
        var oversized = new ArrayList<Requirement>();
        for (int i = 0; i < 33; i++) oversized.add(new Requirement("version-" + i, HASH, "member-public"));
        assertThrows(IllegalArgumentException.class, () -> service.checkForCommand(oversized));
        verifyNoInteractions(proof, roots);
    }

    @Test void rootLocksUseStableOrderAndMutableControlIsRereadAfterLocking() {
        var versionZ = new HashMap<>(version); versionZ.put("announcement_pid", "root-z");
        var versionA = new HashMap<>(version); versionA.put("announcement_pid", "root-a");
        when(proof.version(1L, "version-z")).thenReturn(versionZ);
        when(proof.version(1L, "version-a")).thenReturn(versionA);
        service.checkForCommand(List.of(new Requirement("version-z", HASH, "member-public"), new Requirement("version-a", HASH, "member-public")));
        var order = inOrder(roots, proof);
        order.verify(roots).lock(1L, "root-a"); order.verify(roots).lock(1L, "root-z");
        order.verify(proof).databaseNow(); order.verify(proof).version(1L, "version-z");
    }
}
