package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ConflictException;
import com.auraboot.framework.plugin.extension.approval.ApprovalContract;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnnouncementApprovalReconciliationTest {
    @Mock AnnouncementAccessService access;
    @Mock AnnouncementApprovalMapper submissions;
    @Mock AnnouncementLifecycleMapper records;
    @Mock AnnouncementReleaseMapper releases;
    @Mock AnnouncementApprovalCatalog catalog;
    @Mock WorkflowCapabilityRegistry workflows;
    @Mock AnnouncementApprovalBindings bindings;
    @Mock AnnouncementCodec codec;
    @InjectMocks AnnouncementApprovalService service;
    AutoCloseable mocks;

    @BeforeEach void setup() {
        mocks=MockitoAnnotations.openMocks(this);
        MetaContext.setContext(1L,7L,"author","Author");
        var record=new Announcement();record.setPid("record");record.setRowVersion(4);
        record.setStatus("pending_approval");record.setCurrentVersionPid("version");
        when(access.manage("record","manage",true)).thenReturn(record);
        when(records.claim(any(),any(),any(),any(),any())).thenReturn(1);
        when(records.finish(any(),any(),any(),any(),any(),any())).thenReturn(1);
        when(catalog.available()).thenReturn(true);
        when(releases.version(1L,"version")).thenReturn(Map.of("current_submission_pid","submission"));
        when(submissions.lock(1L,"submission")).thenReturn(Map.of("pid","submission","state","running","provider_id","bpm"));
        when(workflows.providerId(ApprovalContract.STATUS)).thenReturn("bpm");
        when(submissions.reconcile(1L,"submission")).thenReturn(1);
        when(submissions.bump(1L,"record",7L,4)).thenReturn(1);
    }
    @AfterEach void clear() throws Exception {MetaContext.clear();mocks.close();}

    @Test void recoveryQueuesTheExistingSubmissionWithoutCallingOrDecidingTheProvider() {
        var result=service.execute(AnnouncementApprovalService.RECONCILE,"record",Map.of(),"reconcile",4);
        assertThat(result).containsEntry("submissionId","submission").containsEntry("operationState","queued").containsEntry("row_version",5);
        var order=inOrder(access,releases,submissions);
        order.verify(access).manage("record","manage",true);
        order.verify(releases).version(1L,"version");
        order.verify(submissions).lock(1L,"submission");
        order.verify(submissions).reconcile(1L,"submission");
        verify(workflows,never()).execute(anyString(),any());
    }
    @Test void changedProviderCannotResumeAnOldSubmission() {
        when(workflows.providerId(ApprovalContract.STATUS)).thenReturn("replacement");
        assertThatThrownBy(()->service.execute(AnnouncementApprovalService.RECONCILE,"record",Map.of(),"reconcile",4)).isInstanceOf(BusinessException.class);
        verify(submissions,never()).reconcile(any(),any());
    }
    @Test void anAdministratorCannotSupplyAnApprovalResult() {
        assertThatThrownBy(()->service.execute(AnnouncementApprovalService.RECONCILE,"record",Map.of("state","approved"),"reconcile",4)).isInstanceOf(BusinessException.class);
        verify(records,never()).claim(any(),any(),any(),any(),any());
    }
    @Test void staleRevisionCannotRequeueAnotherCurrentState() {
        assertThatThrownBy(()->service.execute(AnnouncementApprovalService.RECONCILE,"record",Map.of(),"reconcile",3)).isInstanceOf(ConflictException.class);
        verify(submissions,never()).reconcile(any(),any());
    }
    @Test void terminalSubmissionCannotBeReopened() {
        when(submissions.lock(1L,"submission")).thenReturn(Map.of("state","approved"));
        assertThatThrownBy(()->service.execute(AnnouncementApprovalService.RECONCILE,"record",Map.of(),"reconcile",4)).isInstanceOf(BusinessException.class);
        verify(submissions,never()).reconcile(any(),any());
    }
}
