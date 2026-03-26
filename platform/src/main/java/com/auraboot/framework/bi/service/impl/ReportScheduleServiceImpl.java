package com.auraboot.framework.bi.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.auraboot.framework.bi.dao.entity.ReportSchedule;
import com.auraboot.framework.bi.dao.mapper.ReportScheduleMapper;
import com.auraboot.framework.bi.dto.ReportScheduleRequest;
import com.auraboot.framework.bi.dto.ReportScheduleResponse;
import com.auraboot.framework.bi.service.ReportDeliveryService;
import com.auraboot.framework.bi.service.ReportScheduleService;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * CRUD and scheduling management for report delivery schedules.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportScheduleServiceImpl implements ReportScheduleService {

    private final ReportScheduleMapper reportScheduleMapper;
    private final ReportDeliveryService reportDeliveryService;

    @Override
    public List<ReportScheduleResponse> listSchedules(Long tenantId) {
        List<ReportSchedule> schedules = reportScheduleMapper.findByTenantId(tenantId);
        return schedules.stream().map(this::toResponse).toList();
    }

    @Override
    public ReportScheduleResponse getSchedule(Long id, Long tenantId) {
        ReportSchedule schedule = getAndValidate(id, tenantId);
        return toResponse(schedule);
    }

    @Override
    public ReportScheduleResponse createSchedule(ReportScheduleRequest request, Long tenantId, Long userId) {
        ReportSchedule entity = new ReportSchedule();
        entity.setPid(UlidGenerator.generate());
        entity.setTenantId(tenantId);
        entity.setName(request.getName());
        entity.setReportId(request.getReportId());
        entity.setScheduleCron(request.getScheduleCron());
        entity.setRecipients(request.getRecipients());
        entity.setFormat(request.getFormat() != null ? request.getFormat() : "pdf");
        entity.setSubjectTemplate(request.getSubjectTemplate());
        entity.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());
        entity.setCreatedBy(userId);
        entity.setDeletedFlag(false);

        reportScheduleMapper.insert(entity);
        log.info("Created report schedule: id={}, name={}, reportId={}", entity.getId(), entity.getName(), entity.getReportId());

        return toResponse(entity);
    }

    @Override
    public ReportScheduleResponse updateSchedule(Long id, ReportScheduleRequest request, Long tenantId) {
        ReportSchedule entity = getAndValidate(id, tenantId);

        entity.setName(request.getName());
        entity.setReportId(request.getReportId());
        entity.setScheduleCron(request.getScheduleCron());
        entity.setRecipients(request.getRecipients());
        entity.setFormat(request.getFormat());
        entity.setSubjectTemplate(request.getSubjectTemplate());
        entity.setEnabled(request.getEnabled());
        entity.setUpdatedAt(new Date());

        reportScheduleMapper.updateById(entity);
        log.info("Updated report schedule: id={}", id);

        return toResponse(entity);
    }

    @Override
    public void deleteSchedule(Long id, Long tenantId) {
        ReportSchedule entity = getAndValidate(id, tenantId);
        entity.setDeletedFlag(true);
        entity.setUpdatedAt(new Date());
        reportScheduleMapper.updateById(entity);
        log.info("Soft-deleted report schedule: id={}", id);
    }

    @Override
    public void testSend(Long id, Long tenantId) {
        ReportSchedule entity = getAndValidate(id, tenantId);
        log.info("Triggering test send for schedule: id={}, reportId={}", id, entity.getReportId());
        reportDeliveryService.generateAndSend(entity);

        // Update last run info
        entity.setLastRunAt(new Date());
        entity.setLastRunStatus("success");
        entity.setLastRunError(null);
        entity.setUpdatedAt(new Date());
        reportScheduleMapper.updateById(entity);
    }

    private ReportSchedule getAndValidate(Long id, Long tenantId) {
        ReportSchedule entity = reportScheduleMapper.selectById(id);
        if (entity == null || Boolean.TRUE.equals(entity.getDeletedFlag())) {
            throw new IllegalArgumentException("Report schedule not found: " + id);
        }
        if (!entity.getTenantId().equals(tenantId)) {
            throw new SecurityException("Access denied to report schedule: " + id);
        }
        return entity;
    }

    private ReportScheduleResponse toResponse(ReportSchedule entity) {
        ReportScheduleResponse resp = new ReportScheduleResponse();
        resp.setId(entity.getId());
        resp.setPid(entity.getPid());
        resp.setName(entity.getName());
        resp.setReportId(entity.getReportId());
        resp.setScheduleCron(entity.getScheduleCron());
        resp.setRecipients(entity.getRecipients());
        resp.setFormat(entity.getFormat());
        resp.setSubjectTemplate(entity.getSubjectTemplate());
        resp.setEnabled(entity.getEnabled());
        resp.setLastRunAt(entity.getLastRunAt());
        resp.setNextRunAt(entity.getNextRunAt());
        resp.setLastRunStatus(entity.getLastRunStatus());
        resp.setLastRunError(entity.getLastRunError());
        resp.setCreatedAt(entity.getCreatedAt());
        resp.setUpdatedAt(entity.getUpdatedAt());
        resp.setCreatedBy(entity.getCreatedBy());
        return resp;
    }
}
