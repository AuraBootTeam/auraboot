package com.auraboot.framework.bi;

import com.auraboot.framework.bi.dao.entity.ReportSchedule;
import com.auraboot.framework.bi.dao.mapper.ReportScheduleMapper;
import com.auraboot.framework.bi.dto.ReportScheduleRequest;
import com.auraboot.framework.bi.dto.ReportScheduleResponse;
import com.auraboot.framework.bi.service.ReportDeliveryService;
import com.auraboot.framework.bi.service.impl.ReportScheduleServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReportScheduleService.
 * Covers CRUD operations, validation, and tenant isolation.
 */
@ExtendWith(MockitoExtension.class)
class ReportScheduleServiceTest {

    @Mock
    private ReportScheduleMapper reportScheduleMapper;

    @Mock
    private ReportDeliveryService reportDeliveryService;

    @InjectMocks
    private ReportScheduleServiceImpl reportScheduleService;

    private final Long TENANT_ID = 1L;
    private final Long USER_ID = 100L;

    private ReportScheduleRequest validRequest;

    @BeforeEach
    void setUp() {
        validRequest = new ReportScheduleRequest();
        validRequest.setName("Weekly Sales Report");
        validRequest.setReportId("report-001");
        validRequest.setScheduleCron("0 0 8 * * MON");
        validRequest.setRecipients(List.of("admin@test.com", "manager@test.com"));
        validRequest.setFormat("pdf");
        validRequest.setSubjectTemplate("Sales Report: ${reportName} - ${date}");
        validRequest.setEnabled(true);
    }

    @Test
    void createSchedule_withValidRequest_createsAndReturnsSchedule() {
        // Arrange
        when(reportScheduleMapper.insert(any(com.auraboot.framework.bi.dao.entity.ReportSchedule.class))).thenReturn(1);

        // Act
        ReportScheduleResponse response = reportScheduleService.createSchedule(validRequest, TENANT_ID, USER_ID);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getName()).isEqualTo("Weekly Sales Report");
        assertThat(response.getReportId()).isEqualTo("report-001");
        assertThat(response.getScheduleCron()).isEqualTo("0 0 8 * * MON");
        assertThat(response.getRecipients()).hasSize(2);
        assertThat(response.getFormat()).isEqualTo("pdf");
        assertThat(response.getEnabled()).isTrue();
        assertThat(response.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(response.getPid()).isNotNull();

        // Verify entity was saved
        ArgumentCaptor<ReportSchedule> captor = ArgumentCaptor.forClass(ReportSchedule.class);
        verify(reportScheduleMapper).insert(captor.capture());
        ReportSchedule saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getDeletedFlag()).isFalse();
    }

    @Test
    void listSchedules_returnsSchedulesForTenant() {
        // Arrange
        ReportSchedule entity = buildEntity(1L, TENANT_ID);
        when(reportScheduleMapper.findByTenantId(TENANT_ID)).thenReturn(List.of(entity));

        // Act
        List<ReportScheduleResponse> result = reportScheduleService.listSchedules(TENANT_ID);

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Test Schedule");
    }

    @Test
    void getSchedule_existingId_returnsSchedule() {
        ReportSchedule entity = buildEntity(1L, TENANT_ID);
        when(reportScheduleMapper.selectById(1L)).thenReturn(entity);

        ReportScheduleResponse response = reportScheduleService.getSchedule(1L, TENANT_ID);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(1L);
    }

    @Test
    void getSchedule_wrongTenant_throwsSecurityException() {
        ReportSchedule entity = buildEntity(1L, 999L); // different tenant
        when(reportScheduleMapper.selectById(1L)).thenReturn(entity);

        assertThatThrownBy(() -> reportScheduleService.getSchedule(1L, TENANT_ID))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void getSchedule_nonExistentId_throwsIllegalArgument() {
        when(reportScheduleMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> reportScheduleService.getSchedule(999L, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void updateSchedule_validRequest_updatesFields() {
        ReportSchedule entity = buildEntity(1L, TENANT_ID);
        when(reportScheduleMapper.selectById(1L)).thenReturn(entity);
        when(reportScheduleMapper.updateById(any(com.auraboot.framework.bi.dao.entity.ReportSchedule.class))).thenReturn(1);

        validRequest.setName("Updated Report");
        ReportScheduleResponse response = reportScheduleService.updateSchedule(1L, validRequest, TENANT_ID);

        assertThat(response.getName()).isEqualTo("Updated Report");
        verify(reportScheduleMapper).updateById(any(com.auraboot.framework.bi.dao.entity.ReportSchedule.class));
    }

    @Test
    void deleteSchedule_softDeletes() {
        ReportSchedule entity = buildEntity(1L, TENANT_ID);
        when(reportScheduleMapper.selectById(1L)).thenReturn(entity);
        when(reportScheduleMapper.updateById(any(com.auraboot.framework.bi.dao.entity.ReportSchedule.class))).thenReturn(1);

        reportScheduleService.deleteSchedule(1L, TENANT_ID);

        ArgumentCaptor<ReportSchedule> captor = ArgumentCaptor.forClass(ReportSchedule.class);
        verify(reportScheduleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getDeletedFlag()).isTrue();
    }

    @Test
    void testSend_triggersDeliveryAndUpdatesStatus() {
        ReportSchedule entity = buildEntity(1L, TENANT_ID);
        when(reportScheduleMapper.selectById(1L)).thenReturn(entity);
        when(reportScheduleMapper.updateById(any(com.auraboot.framework.bi.dao.entity.ReportSchedule.class))).thenReturn(1);
        doNothing().when(reportDeliveryService).generateAndSend(any());

        reportScheduleService.testSend(1L, TENANT_ID);

        verify(reportDeliveryService).generateAndSend(entity);

        ArgumentCaptor<ReportSchedule> captor = ArgumentCaptor.forClass(ReportSchedule.class);
        verify(reportScheduleMapper).updateById(captor.capture());
        ReportSchedule updated = captor.getValue();
        assertThat(updated.getLastRunAt()).isNotNull();
        assertThat(updated.getLastRunStatus()).isEqualTo("success");
    }

    @Test
    void getSchedule_deletedSchedule_throwsIllegalArgument() {
        ReportSchedule entity = buildEntity(1L, TENANT_ID);
        entity.setDeletedFlag(true);
        when(reportScheduleMapper.selectById(1L)).thenReturn(entity);

        assertThatThrownBy(() -> reportScheduleService.getSchedule(1L, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    private ReportSchedule buildEntity(Long id, Long tenantId) {
        ReportSchedule entity = new ReportSchedule();
        entity.setId(id);
        entity.setPid("test-pid-" + id);
        entity.setTenantId(tenantId);
        entity.setName("Test Schedule");
        entity.setReportId("report-001");
        entity.setScheduleCron("0 0 8 * * MON");
        entity.setRecipients(List.of("test@test.com"));
        entity.setFormat("pdf");
        entity.setEnabled(true);
        entity.setDeletedFlag(false);
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());
        entity.setCreatedBy(USER_ID);
        return entity;
    }
}
