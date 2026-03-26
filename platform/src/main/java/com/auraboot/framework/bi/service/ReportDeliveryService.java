package com.auraboot.framework.bi.service;

import com.auraboot.framework.bi.dao.entity.ReportSchedule;

/**
 * Service responsible for generating report output and sending via email.
 * Uses PrintService for PDF, and JavaMailSender for delivery.
 */
public interface ReportDeliveryService {

    /**
     * Generate report and send to all recipients defined in the schedule.
     *
     * @param schedule the report schedule to execute
     */
    void generateAndSend(ReportSchedule schedule);
}
