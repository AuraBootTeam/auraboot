package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.AnnouncementAcknowledgementAccessor.*;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real evaluator with hermetic mapper doubles; no PostgreSQL lock or persisted acknowledgement claim. */
class AnnouncementSubjectAcknowledgementServiceTest {
    final AnnouncementAcknowledgementMapper proof=mock(AnnouncementAcknowledgementMapper.class);
    final AnnouncementLifecycleMapper roots=mock(AnnouncementLifecycleMapper.class);
    final AnnouncementSubjectAcknowledgementService service=new AnnouncementSubjectAcknowledgementService(proof,roots,new AnnouncementCodec(new ObjectMapper()));
    final Instant now=Instant.parse("2026-10-28T00:00:00Z");final String hash="a".repeat(64);
    Map<String,Object> version,recipient;Announcement root;
    @BeforeEach void setup(){
        MetaContext.setContext(1L,2L,"operator","Operator");MetaContext.setMemberId(3L);
        TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        when(proof.currentMember(1L,4L,"applicant")).thenReturn(Map.of("member_id",5L,"member_pid","applicant-member","user_pid","applicant"));
        when(proof.databaseNow()).thenReturn(now);
        version=new HashMap<>(Map.of("announcement_pid","notice","version_pid","version","snapshot_hash",hash,"snapshot","{\"policy\":{\"acknowledgementRequired\":true}}","publication_state","published","expires_at",now.plusSeconds(60)));
        recipient=new HashMap<>(Map.of("first_read_at",now.minusSeconds(2),"acknowledged_at",now.minusSeconds(1),"acknowledged_hash",hash));
        when(proof.version(1L,"version")).thenAnswer(c->new HashMap<>(version));when(proof.recipient(1L,"version","applicant","applicant-member")).thenAnswer(c->recipient);
        root=new Announcement();root.setPid("notice");root.setStatus("published");root.setActiveVersionPid("version");root.setDeletedFlag(false);when(roots.lock(1L,"notice")).thenReturn(root);
    }
    @AfterEach void clear(){TransactionSynchronizationManager.clear();MetaContext.clear();}
    List<Check> check(){return service.checkSubject(1L,4L,"applicant","applicant-member",List.of(new Requirement("version",hash,"applicant-member")));}
    @Test void readsExistingOtherSubjectWithoutReplacingTheOperator(){
        var value=check().getFirst();assertEquals(Status.SATISFIED,value.status());assertEquals(hash,value.acknowledgedHash());assertEquals(now.minusSeconds(1),value.acknowledgedAt());
        assertEquals("operator",MetaContext.getCurrentUserPid());assertEquals(2L,MetaContext.getCurrentUserId());assertEquals(3L,MetaContext.getCurrentMemberId());
        verify(proof).recipient(1L,"version","applicant","applicant-member");verify(proof,never()).recipient(1L,"version","operator","applicant-member");
        assertTrue(Arrays.stream(AnnouncementAcknowledgementMapper.class.getDeclaredMethods()).allMatch(m->m.isAnnotationPresent(org.apache.ibatis.annotations.Select.class)),"Read bridge has no write mapper");
    }
    @ParameterizedTest @ValueSource(strings={"tenant","no-tx","read-only","no-sync"}) void rejectsInvalidTransactions(String mode){
        switch(mode){case "tenant"->MetaContext.setContext(9L,2L,"operator","Wrong");case "no-tx"->TransactionSynchronizationManager.setActualTransactionActive(false);case "read-only"->TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);case "no-sync"->TransactionSynchronizationManager.clearSynchronization();}
        assertThrows(IllegalStateException.class,this::check);verifyNoInteractions(proof,roots);
    }
    @Test void disabledSubjectCannotUseAReceipt(){when(proof.currentMember(1L,4L,"applicant")).thenReturn(null);assertEquals(Status.MEMBER_CHANGED,check().getFirst().status());verifyNoInteractions(roots);}
    @ParameterizedTest @ValueSource(strings={"ack","member","version","expiry","audience"}) void revalidatesLateChangesBeforeCommit(String mode){
        check();switch(mode){case "ack"->recipient.put("acknowledged_hash","b".repeat(64));case "member"->when(proof.currentMember(1L,4L,"applicant")).thenReturn(null);case "version"->root.setActiveVersionPid("replacement");case "expiry"->version.put("expires_at",now);case "audience"->when(proof.recipient(1L,"version","applicant","applicant-member")).thenReturn(null);}
        assertThrows(RuntimeException.class,()->TransactionSynchronizationManager.getSynchronizations().forEach(s->s.beforeCommit(false)));
    }
}
