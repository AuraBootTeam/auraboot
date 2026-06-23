package com.auraboot.framework.bi.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.dao.entity.ReportSchedule;
import com.auraboot.framework.bi.dto.ReportExportFile;
import com.auraboot.framework.bi.dto.ReportExportRequest;
import com.auraboot.framework.bi.service.ReportDeliveryService;
import com.auraboot.framework.bi.service.ReportExportService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a scheduled report and delivers it as an email attachment.
 *
 * <p>B7 (DDR-2026-06-21-report-export-rendering-source-of-truth): the
 * schedule/delivery path reuses the shared {@link ReportExportService} — the same
 * WYSIWYG renderer as the interactive export — so a scheduled report attachment is
 * the REAL report (charts/tables/pivots), not a placeholder. The delivery path
 * does not depend on designer/browser code; it only calls the export service.
 */
@Slf4j
@Service
public class ReportDeliveryServiceImpl implements ReportDeliveryService {

    private final ReportExportService reportExportService;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    public ReportDeliveryServiceImpl(ReportExportService reportExportService) {
        this.reportExportService = reportExportService;
    }

    @Override
    public void generateAndSend(ReportSchedule schedule) {
        log.info("Generating report for schedule: name={}, reportId={}, format={}",
                schedule.getName(), schedule.getReportId(), schedule.getFormat());

        List<String> recipients = schedule.getRecipients();
        if (recipients == null || recipients.isEmpty()) {
            log.warn("No recipients for schedule {}, skipping", schedule.getId());
            return;
        }

        ReportExportFile report = renderReport(schedule);
        String subject = buildSubject(schedule);
        String body = buildCoverHtml(schedule);

        for (String email : recipients) {
            try {
                sendEmail(email, subject, body, report);
                log.info("Report email sent to {} for schedule {}", email, schedule.getName());
            } catch (Exception e) {
                log.error("Failed to send report email to {} for schedule {}: {}",
                        email, schedule.getName(), e.getMessage());
                throw new RuntimeException("Failed to deliver report to " + email, e);
            }
        }
    }

    /**
     * Render the actual report via the shared export service (WYSIWYG PDF, or Excel
     * when the schedule asks for it). Establishes the schedule's tenant context only
     * when none is already set (e.g. a cron trigger) and restores by clearing — so a
     * caller-supplied context (e.g. an admin test-send) is never clobbered.
     */
    private ReportExportFile renderReport(ReportSchedule schedule) {
        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid(schedule.getReportId());

        boolean establishedContext = false;
        if (!MetaContext.exists() && schedule.getTenantId() != null) {
            MetaContext.setContext(schedule.getTenantId(), schedule.getCreatedBy(), null, "report-scheduler");
            establishedContext = true;
        }
        try {
            return "excel".equalsIgnoreCase(schedule.getFormat())
                    ? reportExportService.exportExcel(request)
                    : reportExportService.exportPdf(request);
        } finally {
            if (establishedContext) {
                MetaContext.clear();
            }
        }
    }

    private String buildSubject(ReportSchedule schedule) {
        String template = schedule.getSubjectTemplate();
        if (template == null || template.isBlank()) {
            template = "Scheduled Report: ${reportName} - ${date}";
        }
        return template
                .replace("${reportName}", schedule.getName() != null ? schedule.getName() : "Report")
                .replace("${date}", LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
    }

    private String buildCoverHtml(ReportSchedule schedule) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><title>Report</title></head>
                <body style="font-family: Arial, sans-serif; padding: 20px;">
                    <h1 style="color: #333;">%s</h1>
                    <p>Generated at: %s</p>
                    <p>The full report is attached.</p>
                    <hr>
                    <p style="color: #666; font-size: 12px;">
                        This is an automated report delivery.
                        To manage your report schedules, visit the Report Schedules page.
                    </p>
                </body>
                </html>
                """.formatted(
                schedule.getName() != null ? schedule.getName() : "Report",
                LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        );
    }

    private void sendEmail(String to, String subject, String body, ReportExportFile attachment)
            throws MessagingException {
        if (mailSender == null) {
            log.warn("JavaMailSender not configured — email skipped for recipient {}", to);
            return;
        }
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(body, true);
        helper.setFrom("noreply@auraboot.com");
        if (attachment != null && attachment.getBytes() != null && attachment.getBytes().length > 0) {
            String filename = StringUtils.hasText(attachment.getFilename())
                    ? attachment.getFilename()
                    : "report";
            helper.addAttachment(filename, new ByteArrayResource(attachment.getBytes()),
                    attachment.getContentType());
        }
        mailSender.send(message);
    }
}
