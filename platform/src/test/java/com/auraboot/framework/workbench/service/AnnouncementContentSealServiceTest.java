package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.dao.mapper.FileMapper;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.constant.StorageType;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.plugin.extension.*;
import com.auraboot.framework.plugin.extension.AnnouncementSealedContentAccessor.*;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.*;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.auraboot.framework.plugin.extension.approval.ApprovalContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnnouncementContentSealServiceTest {
    static final String ROOT="01ARZ3NDEKTSV4RRFFQ69G5FAV",VERSION="01ARZ3NDEKTSV4RRFFQ69G5FAW",FILE="01ARZ3NDEKTSV4RRFFQ69G5FAX";
    final AnnouncementContentSealMapper seals=mock(AnnouncementContentSealMapper.class);
    final AnnouncementAccessService access=mock(AnnouncementAccessService.class);
    final AnnouncementApprovalProofService approval=mock(AnnouncementApprovalProofService.class);
    final AnnouncementApprovalMapper submissions=mock(AnnouncementApprovalMapper.class);
    final AnnouncementApprovalBindings bindings=mock(AnnouncementApprovalBindings.class);
    final AnnouncementCodec codec=new AnnouncementCodec(new ObjectMapper().findAndRegisterModules());
    final FileMapper rows=mock(FileMapper.class);final StorageProvider storage=mock(StorageProvider.class);
    final CommandMembershipLockAccessor locks=mock(CommandMembershipLockAccessor.class);
    final IdentityDirectoryAccessor identities=mock(IdentityDirectoryAccessor.class);
    final PermissionSnapshotCache permissions=mock(PermissionSnapshotCache.class);
    final AnnouncementContentSealService service=new AnnouncementContentSealService(seals,access,approval,submissions,bindings,codec,rows,storage,locks,identities,permissions);
    Map<String,Object> snapshot,stored;String hash;FileEntity file;Map<String,Object> version;Announcement root;
    @BeforeEach void setup() {
        when(permissions.withFreshReads(any())).thenAnswer(i->((java.util.function.Supplier<?>)i.getArgument(0)).get());
        MetaContext.setContext(1L,2L,"actor","actor");MetaContext.setMemberId(3L);
        TransactionSynchronizationManager.initSynchronization();
        snapshot=Map.of("title"," Title ","content","<p>raw\r\n正文</p>","policy",Map.of(
                "attachmentPids",List.of(FILE),"attachmentMetadata",List.of(Map.of("fileId",FILE,"name","proof.txt","size",3,"type","text/plain"))));
        hash=codec.hash(snapshot);
        file=new FileEntity();file.setPid(FILE);file.setStatus("success");file.setDeletedFlag(false);file.setRetentionLocked(true);
        file.setStorageType(StorageType.LOCAL);file.setFileName("private/key.txt");file.setFileSize(3L);file.setOriginalName("proof.txt");file.setMimeType("text/plain");
        when(rows.selectActiveByPidForUpdate(1L,FILE)).thenReturn(file);when(storage.type()).thenReturn(StorageType.LOCAL);
        when(storage.download("private/key.txt")).thenAnswer(i->new ByteArrayInputStream(new byte[]{1,2,3}));
        when(seals.insert(anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenAnswer(i->{
            stored=new HashMap<>(Map.of("announcement_pid",ROOT,"snapshot_hash",hash,"projection_schema",AnnouncementDocumentCanonicalizer.SCHEMA,
                    "source_identity_kind","SEAL_OBSERVED_CONTENT","canonical_projection",i.getArgument(4),"document_digest",i.getArgument(5),"seal_identity",i.getArgument(6)));
            return 1;
        });
        when(seals.seal(1L,VERSION)).thenAnswer(i->stored);
        root=new Announcement();root.setPid(ROOT);root.setStatus("published");root.setActiveVersionPid(VERSION);root.setDeletedFlag(false);
        when(access.manage(ROOT,"publish",true)).thenReturn(root);
        version=new HashMap<>(Map.of("announcement_pid",ROOT,"snapshot_hash",hash,"snapshot_schema","announcement.v1","snapshot",codec.encode(snapshot),
                "publication_state","published","authorization_basis","approved","approval_state","approved","current_submission_pid","submission"));
        when(seals.version(1L,VERSION)).thenAnswer(i->version);when(seals.databaseNow()).thenReturn(Instant.parse("2030-01-01T00:00:00Z"));
        var binding=new SubmittedApproval("submission","operation",new BusinessRef("announcement",ROOT,VERSION),"snapshot",hash,"announcement.v1",
                new DefinitionRef("definition",2,"c".repeat(64)),StrictPolicy.required(),"actor","Title",Instant.EPOCH);
        when(submissions.read(1L,"submission")).thenReturn(Map.of("terminal_result_pid","result"));
        when(bindings.read(anyMap())).thenReturn(binding);
    }
    @AfterEach void clear() {TransactionSynchronizationManager.clear();MetaContext.clear();}
    private ExactVersion expected(){return new ExactVersion(ROOT,VERSION,hash,AnnouncementDocumentCanonicalizer.SCHEMA);}
    private void capture(){service.captureFrozenVersion(1L,ROOT,VERSION,hash,codec.encode(snapshot));}
    @Test void officialCaptureAndPublisherProofBindActualBytesWithoutExposingLocator() {
        capture();var proof=service.requirePublished(expected(),"member");
        assertEquals(" Title ",proof.document().title());assertEquals(snapshot.get("content"),proof.document().body());
        assertEquals(3,proof.document().attachments().getFirst().byteLength());
        assertNotEquals(hash,proof.encoded().documentDigest());assertNotEquals(proof.sealIdentity(),proof.encoded().documentDigest());
        assertFalse(proof.encoded().canonicalJson().contains("private/key"));
        assertEquals(2,proof.approvedBinding().definitionVersion());verify(approval).requireValid(1L,"submission",VERSION,hash);
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.beforeCommit(false));
    }
    @Test void historicalMissingSealIsNeverCreatedByProofLookup() {
        assertThrows(IllegalStateException.class,()->service.requirePublished(expected(),"member"));
        verify(seals,never()).insert(anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void changedBytesAndMissingRetentionFailAtReadAndCommit() {
        capture();when(storage.download(anyString())).thenReturn(new ByteArrayInputStream(new byte[]{1,2,4}));
        assertThrows(IllegalStateException.class,()->service.requirePublished(expected(),"member"));
        assertThrows(IllegalStateException.class,()->TransactionSynchronizationManager.getSynchronizations().forEach(s->s.beforeCommit(false)));
        file.setRetentionLocked(false);assertThrows(IllegalStateException.class,()->service.requirePublished(expected(),"member"));
    }
    @Test void unavailablePublicationAndApprovalStatesCannotBeUpgradedByASeal() {
        capture();
        for(String state:List.of("scheduled","withdrawn","expired")) {
            version.put("publication_state",state);assertThrows(IllegalStateException.class,()->service.requirePublished(expected(),"member"));
        }
        version.put("publication_state","published");version.put("authorization_basis","exempt");
        assertThrows(IllegalStateException.class,()->service.requirePublished(expected(),"member"));
        version.put("authorization_basis","approved");root.setActiveVersionPid(ROOT);
        assertThrows(IllegalStateException.class,()->service.requirePublished(expected(),"member"));
    }
    @Test void releaseRechecksOnlyAnExistingSealAndNeverBackfillsLegacyVersions() {
        service.verifyForRelease(1L,VERSION,hash,snapshot);
        verify(seals,never()).insert(anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
        capture();service.verifyForRelease(1L,VERSION,hash,snapshot);
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.beforeCommit(false));
        file.setRetentionLocked(false);
        assertThrows(IllegalStateException.class,()->service.verifyForRelease(1L,VERSION,hash,snapshot));
    }
    @Test void actorLocksReuseTheOfficialExactMembershipService() {
        when(identities.findEnabledTenantMembership(1L,"actor")).thenReturn(new IdentityDirectoryAccessor.TenantUserIdentity(
                new IdentityDirectoryAccessor.UserIdentity(2L,"actor","actor","Actor"),"member",3L));
        assertEquals("member",service.lockActor());
        verify(locks).requireEnabledMemberships(List.of(new CommandMembershipLockAccessor.MembershipAssertion("actor","member")));
        MetaContext.setMemberId(4L);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,service::lockActor);
    }
    @Test void releaseCommitRetainsPublisherAfterWorkerClearsContextInTheOriginalTransaction() throws Exception {
        TransactionSynchronizationManager.clearSynchronization();
        DataSource dataSource=mock(DataSource.class);Connection connection=mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setName("announcement-release-seal-test");
        MetaContext.setContext(1L,2L,"actor","actor",Set.of(7L));MetaContext.setMemberId(3L);
        var publisher=MetaContext.snapshot();Thread originalThread=Thread.currentThread();
        when(seals.seal(1L,VERSION)).thenAnswer(i->{
            assertEquals(1L,MetaContext.getCurrentTenantId());
            assertEquals(publisher,MetaContext.snapshot());
            assertSame(originalThread,Thread.currentThread());
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertSame(connection,DataSourceUtils.getConnection(dataSource));
            return stored;
        });
        doAnswer(i->{assertFalse(MetaContext.exists(),"Publisher context must not leak into the worker");return null;}).when(connection).commit();
        transaction.executeWithoutResult(status->{
            capture();service.verifyForRelease(1L,VERSION,hash,snapshot);
            // Matches ReleaseService.perform's finally before the outer Spring AOP commit.
            MetaContext.clear();
        });
        assertFalse(MetaContext.exists());verify(connection).commit();verify(connection,never()).rollback();
        verify(dataSource,times(1)).getConnection();verify(seals,times(5)).seal(1L,VERSION);
    }
    @Test void commitTamperingStillRollsBackAndRestoresAnUnrelatedOuterContext() throws Exception {
        TransactionSynchronizationManager.clearSynchronization();
        DataSource dataSource=mock(DataSource.class);Connection connection=mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var publisher=MetaContext.snapshot();
        var outer=new java.util.concurrent.atomic.AtomicReference<MetaContext.Snapshot>();
        when(seals.seal(1L,VERSION)).thenAnswer(i->{
            assertEquals(1L,MetaContext.getCurrentTenantId());assertEquals(publisher,MetaContext.snapshot());
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertSame(connection,DataSourceUtils.getConnection(dataSource));return stored;
        });
        assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(status->{
            capture();service.verifyForRelease(1L,VERSION,hash,snapshot);
            when(storage.download("private/key.txt")).thenAnswer(i->new ByteArrayInputStream(new byte[]{1,2,4}));
            MetaContext.setContext(9L,22L,"scheduler","Scheduler",Set.of(88L));MetaContext.setMemberId(23L);
            outer.set(MetaContext.snapshot());
        }));
        assertEquals(outer.get(),MetaContext.snapshot(),"Failed seal verification must restore the prior caller exactly");
        verify(connection).rollback();verify(connection,never()).commit();verify(dataSource,times(1)).getConnection();
    }
    @Test void sameTransactionExpiryAndMalformedMetadataFailClosed() {
        capture();version.put("expires_at",Instant.parse("2029-12-31T23:59:59Z"));
        assertThrows(IllegalStateException.class,()->service.requirePublished(expected(),"member"));
        version.remove("expires_at");file.setFileSize(4L);
        assertThrows(IllegalStateException.class,()->service.requirePublished(expected(),"member"));
    }
}
