package com.auraboot.framework.bi.service;

import com.auraboot.framework.bi.dto.ReportScheduleRequest;
import com.auraboot.framework.bi.dto.ReportScheduleResponse;

import java.util.List;

/**
 * Service for managing report delivery schedules.
 */
public interface ReportScheduleService {

    List<ReportScheduleResponse> listSchedules(Long tenantId);

    ReportScheduleResponse getSchedule(Long id, Long tenantId);

    ReportScheduleResponse createSchedule(ReportScheduleRequest request, Long tenantId, Long userId);

    ReportScheduleResponse updateSchedule(Long id, ReportScheduleRequest request, Long tenantId);

    void deleteSchedule(Long id, Long tenantId);

    /**
     * Trigger an immediate test delivery for the given schedule.
     */
    void testSend(Long id, Long tenantId);
}
