package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.dao.mapper.FileMapper;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.plugin.extension.*;
import com.auraboot.framework.plugin.extension.AnnouncementDocumentCanonicalizer.*;
import com.auraboot.framework.plugin.extension.AnnouncementSealedContentAccessor.*;
import com.auraboot.framework.workbench.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Official sealing and verification; reading a proof never creates missing evidence. */
@Service
@RequiredArgsConstructor
public class AnnouncementContentSealService {
    private final AnnouncementContentSealMapper seals;
    private final AnnouncementAccessService access;
    private final AnnouncementApprovalProofService approval;
    private final AnnouncementApprovalMapper submissions;
    private final AnnouncementApprovalBindings bindings;
    private final AnnouncementCodec codec;
    private final FileMapper fileRows;
    private final StorageProvider storage;
    private final CommandMembershipLockAccessor membershipLocks;
    private final IdentityDirectoryAccessor identities;
    private final com.auraboot.framework.permission.service.impl.PermissionSnapshotCache permissionSnapshots;

    /** Must precede announcement root locks on every official new-seal/release path. */
    @Transactional(propagation=Propagation.MANDATORY)
    public String lockActor() {
        var identity=identities.findEnabledTenantMembership(MetaContext.getCurrentTenantId(),MetaContext.getCurrentUserPid());
        if(identity==null || identity.user()==null
                || !Objects.equals(identity.user().id(),MetaContext.getCurrentUserId())
                || !Objects.equals(identity.memberId(),MetaContext.getCurrentMemberId())) throw denied();
        membershipLocks.requireEnabledMemberships(List.of(new CommandMembershipLockAccessor.MembershipAssertion(
                identity.user().pid(),identity.memberPid())));
        return identity.memberPid();
    }

    /** Called only after official freeze, in the very same transaction as its version INSERT. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void captureFrozenVersion(Long tenant,String root,String version,String snapshotHash,String snapshotJson) {
        requireTenant(tenant);
        if(seals.seal(tenant,version)!=null) throw unavailable();
        var snapshot=codec.object(snapshotJson);
        if(!snapshotHash.equals(codec.hash(snapshot))) throw unavailable();
        var document=document(snapshot,observeAttachments(tenant,snapshot));
        var encoded=AnnouncementDocumentCanonicalizer.encode(document);
        if(seals.insert(tenant,root,version,snapshotHash,encoded.canonicalJson(),encoded.documentDigest(),sealIdentity(tenant,root,version,snapshotHash,encoded.documentDigest()))!=1)
            throw new IllegalStateException("Official content seal was not persisted");
        verifyBeforeCommit(tenant,version,snapshotHash,snapshot);
    }

    /** Publication checks new evidence when present; legacy versions remain readable but never gain a seal. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void verifyForRelease(Long tenant,String version,String snapshotHash,Map<String,Object> snapshot) {
        requireTenant(tenant);
        if(seals.seal(tenant,version)!=null) {
            verifySeal(tenant,version,snapshotHash,snapshot);
            verifyBeforeCommit(tenant,version,snapshotHash,snapshot);
        }
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public PublishedContent requirePublished(ExactVersion expected,String memberPid) {
        Long tenant=MetaContext.getCurrentTenantId(); requireTenant(tenant);
        // Flush the session before authoritative root/permission reads, including same-TX changes.
        seals.version(tenant,expected.versionPid());
        var root=permissionSnapshots.withFreshReads(() -> access.manage(expected.announcementPid(),"publish",true));
        var version=seals.version(tenant,expected.versionPid());
        Instant now=seals.databaseNow();
        if(version==null || !expected.announcementPid().equals(version.get("announcement_pid"))
                || !expected.expectedSnapshotHash().equals(version.get("snapshot_hash"))
                || !"announcement.v1".equals(version.get("snapshot_schema"))
                || !"published".equals(root.getStatus()) || Boolean.TRUE.equals(root.getDeletedFlag())
                || !expected.versionPid().equals(root.getActiveVersionPid())
                || !"published".equals(version.get("publication_state"))
                || !"approved".equals(version.get("authorization_basis")) || !"approved".equals(version.get("approval_state")))
            throw unavailable();
        Instant expiry=version.get("expires_at")==null ? null : AnnouncementDeliveryPlanResolver.instant(version.get("expires_at"));
        if(expiry!=null && !now.isBefore(expiry)) throw unavailable();
        String submission=(String)version.get("current_submission_pid");
        if(submission==null) throw unavailable();
        approval.requireValid(tenant,submission,expected.versionPid(),expected.expectedSnapshotHash());
        var row=submissions.read(tenant,submission);
        if(row==null || row.get("terminal_result_pid")==null) throw unavailable();
        var binding=bindings.read(row);
        if(!expected.announcementPid().equals(binding.businessRef().recordPid())
                || !"announcement.v1".equals(binding.snapshotSchema())) throw unavailable();
        var snapshot=codec.object((String)version.get("snapshot"));
        if(!expected.expectedSnapshotHash().equals(codec.hash(snapshot))
                || !Objects.equals(expiry,snapshot.get("expiresAt")==null ? null : Instant.parse(snapshot.get("expiresAt").toString())))
            throw unavailable();
        var document=verifySeal(tenant,expected.versionPid(),expected.expectedSnapshotHash(),snapshot);
        var encoded=AnnouncementDocumentCanonicalizer.encode(document);
        var definition=binding.definition();
        return new PublishedContent(expected,(String)version.get("snapshot_schema"),expected.expectedSnapshotHash(),
                document,encoded,new ApprovedBinding(submission,(String)row.get("terminal_result_pid"),definition.pid(),
                definition.version(),definition.hash()),(String)seals.seal(tenant,expected.versionPid()).get("seal_identity"),memberPid,now,expiry);
    }

    private void verifyBeforeCommit(Long tenant,String version,String hash,Map<String,Object> snapshot) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) { verifySeal(tenant,version,hash,snapshot); }
                });
    }

    private Document verifySeal(Long tenant,String version,String hash,Map<String,Object> snapshot) {
        var seal=seals.seal(tenant,version);
        if(seal==null || !hash.equals(seal.get("snapshot_hash"))
                || !AnnouncementDocumentCanonicalizer.SCHEMA.equals(seal.get("projection_schema"))
                || !"SEAL_OBSERVED_CONTENT".equals(seal.get("source_identity_kind"))) throw unavailable();
        var current=document(snapshot,observeAttachments(tenant,snapshot));
        var encoded=AnnouncementDocumentCanonicalizer.encode(current);
        if(!encoded.canonicalJson().equals(seal.get("canonical_projection"))
                || !encoded.documentDigest().equals(seal.get("document_digest"))
                || !sealIdentity(tenant,(String)seal.get("announcement_pid"),version,hash,encoded.documentDigest())
                    .equals(seal.get("seal_identity"))) throw unavailable();
        return current;
    }

    private Document document(Map<String,Object> snapshot,List<Attachment> attachments) {
        if(!(snapshot.get("title") instanceof String title) || !(snapshot.get("content") instanceof String content)) throw unavailable();
        return new Document(title,content,attachments);
    }

    private List<Attachment> observeAttachments(Long tenant,Map<String,Object> snapshot) {
        if(!(snapshot.get("policy") instanceof Map<?,?> policy)
                || !(policy.get("attachmentPids") instanceof List<?> pids)
                || !(policy.get("attachmentMetadata") instanceof List<?> metadata) || pids.size()!=metadata.size()) throw unavailable();
        var sorted=new TreeSet<String>();
        for(Object value:pids) {
            if(!(value instanceof String pid)) throw unavailable();
            AnnouncementDocumentCanonicalizer.requirePid(pid);
            if(!sorted.add(pid)) throw unavailable();
        }
        var result=new ArrayList<Attachment>();
        for(String pid:sorted) {
            var matches=metadata.stream().filter(v->v instanceof Map<?,?> m && pid.equals(m.get("fileId"))).toList();
            if(matches.size()!=1) throw unavailable();
            var declared=(Map<?,?>)matches.getFirst();
            var file=fileRows.selectActiveByPidForUpdate(tenant,pid);
            if(file==null || !pid.equals(file.getPid()) || Boolean.TRUE.equals(file.getDeletedFlag())
                    || !"success".equals(file.getStatus()) || !Boolean.TRUE.equals(file.getRetentionLocked())
                    || file.getStorageType()!=storage.type() || file.getFileName()==null || file.getFileName().isBlank()
                    || file.getFileSize()==null || !(declared.get("size") instanceof Number size)
                    || size.longValue()!=file.getFileSize() || !Objects.equals(declared.get("name"),file.getOriginalName())
                    || !Objects.equals(declared.get("type"),file.getMimeType())) throw unavailable();
            var digest=sha(); long length=0;
            try(var input=storage.download(file.getFileName())) {
                if(input==null) throw unavailable();
                byte[] buffer=new byte[65536]; int read;
                while((read=input.read(buffer))!=-1) { length=Math.addExact(length,read); if(length>file.getFileSize()) throw unavailable(); digest.update(buffer,0,read); }
            } catch(IOException | RuntimeException failure) {
                // Provider errors may include private keys/endpoints. Preserve the cause only for host diagnostics.
                throw new IllegalStateException("Sealed attachment source is unavailable",failure);
            }
            if(length!=file.getFileSize()) throw unavailable();
            String bytes=HexFormat.of().formatHex(digest.digest());
            String locator=hash((storage.type().name()+"\u0000"+file.getFileName()).getBytes(StandardCharsets.UTF_8));
            String identity=hash(("auraboot.seal.observed-content.v1\n"+locator+"\n"+length+"\n"+bytes).getBytes(StandardCharsets.UTF_8));
            result.add(new Attachment(pid,SourceIdentityKind.SEAL_OBSERVED_CONTENT,identity,file.getOriginalName(),
                    file.getMimeType(),length,bytes));
        }
        return List.copyOf(result);
    }
    private static String sealIdentity(Long tenant,String root,String version,String snapshotHash,String documentDigest) {
        return hash(("auraboot.announcement.content-seal.v1\n"+tenant+"\n"+root+"\n"+version+"\n"+snapshotHash+"\n"+documentDigest)
                .getBytes(StandardCharsets.UTF_8));
    }
    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hash(byte[] value) { return HexFormat.of().formatHex(sha().digest(value)); }
    private static void requireTenant(Long tenant) {
        if(tenant==null || !tenant.equals(MetaContext.getCurrentTenantId())) throw denied();
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Exact enabled publisher membership is required"); }
    private static IllegalStateException unavailable() { return new IllegalStateException("Sealed announcement content proof is unavailable"); }
}
