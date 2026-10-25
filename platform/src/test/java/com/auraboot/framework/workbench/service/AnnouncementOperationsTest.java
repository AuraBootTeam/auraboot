package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnnouncementOperationsTest {
    final AnnouncementOperationsMapper operations=mock(AnnouncementOperationsMapper.class);
    final AnnouncementLifecycleMapper records=mock(AnnouncementLifecycleMapper.class);
    final AnnouncementStatisticsMapper statistics=mock(AnnouncementStatisticsMapper.class);
    final AnnouncementAccessService access=mock(AnnouncementAccessService.class);
    final AnnouncementAudienceService audiences=mock(AnnouncementAudienceService.class);
    final AnnouncementOperationsService service=new AnnouncementOperationsService(operations,records,statistics,access,audiences,
        new AnnouncementCodec(new ObjectMapper().findAndRegisterModules()));
    @BeforeEach void context(){MetaContext.setContext(1L,7L,"author","Author");MetaContext.setMemberId(9L);}
    @AfterEach void clear(){MetaContext.clear();}
    @Test void lookupRequiresTheCommandNamespaceAndCurrentPermissionBeforeReading() {
        assertThatThrownBy(()->service.operation("other:publish","operation")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.operation("announcement:unknown","operation")).isInstanceOf(BusinessException.class);
        when(access.actor("publish")).thenThrow(new AccessDeniedException("Revoked"));
        assertThatThrownBy(()->service.operation("announcement:publish","operation")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(operations);
    }
    @Test void missingOrUncommittedIsUnknownRatherThanFailedAndNeverQueriesAnotherActor() {
        when(access.actor("publish")).thenReturn(7L);
        assertThat(service.operation("announcement:publish","key")).containsExactlyInAnyOrderEntriesOf(Map.of("found",false,"operationState","unknown"));
        verify(operations).operation(1L,7L,9L,"announcement:publish","key");
    }
    @Test void minimalAcceptedProjectionCannotLeakTheStoredAuthorizationEvidence() {
        when(access.actor("publish")).thenReturn(7L);
        var root=new Announcement();root.setOwnerUserId(7L);when(records.readTarget(1L,"record")).thenReturn(root);
        when(operations.operation(1L,7L,9L,"announcement:publish","key")).thenReturn(Map.of("announcement_pid","record","created_at",Instant.EPOCH,
            "result","{\"recordPid\":\"record\",\"versionPid\":\"version\",\"releaseAttemptPid\":\"release\",\"operationState\":\"accepted\",\"actorMemberPid\":\"private\",\"authorizationHash\":\"secret\"}"));
        assertThat(service.operation("announcement:publish","key")).containsEntry("operationState","accepted").containsEntry("releaseAttemptPid","release")
            .doesNotContainKeys("actorMemberPid","authorizationHash","request_hash","result");
        verify(access,never()).manage(any(),any(),anyBoolean());
    }
    @Test void invalidPagingIsRejectedBeforeReadingAnyProtectedVersion() {
        assertThatThrownBy(()->service.deliveries("version",0,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.deliveries("version",1,1000)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(operations,statistics,access);
    }
}
