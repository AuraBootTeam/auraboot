package com.auraboot.framework.bi.service.impl;

import com.auraboot.framework.bi.dao.entity.ReportSchedule;
import com.auraboot.framework.bi.service.ReportDeliveryService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generates report content and delivers via email.
 * Currently generates a simple HTML report body.
 * Can be extended to use PrintService for PDF attachment generation.
 */
@Slf4j
@Service
public class ReportDeliveryServiceImpl implements ReportDeliveryService {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Override
    public void generateAndSend(ReportSchedule schedule) {
        log.info("Generating report for schedule: name={}, reportId={}, format={}",
                schedule.getName(), schedule.getReportId(), schedule.getFormat());

        List<String> recipients = schedule.getRecipients();
        if (recipients == null || recipients.isEmpty()) {
            log.warn("No recipients for schedule {}, skipping", schedule.getId());
            return;
        }

        // Build subject from template
        String subject = buildSubject(schedule);

        // Generate report content (HTML body for now)
        String htmlContent = generateReportHtml(schedule);

        // Send email to each recipient
        for (String email : recipients) {
            try {
                sendEmail(email, subject, htmlContent);
                log.info("Report email sent to {} for schedule {}", email, schedule.getName());
            } catch (Exception e) {
                log.error("Failed to send report email to {} for schedule {}: {}",
                        email, schedule.getName(), e.getMessage());
                throw new RuntimeException("Failed to deliver report to " + email, e);
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

    private String generateReportHtml(ReportSchedule schedule) {
        // Minimal HTML report. In production, this would call PrintService
        // to render the actual report page schema into HTML/PDF.
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><title>Report</title></head>
                <body style="font-family: Arial, sans-serif; padding: 20px;">
                    <h1 style="color: #333;">%s</h1>
                    <p>Report ID: %s</p>
                    <p>Generated at: %s</p>
                    <p>Format: %s</p>
                    <hr>
                    <p style="color: #666; font-size: 12px;">
                        This is an automated report delivery.
                        To manage your report schedules, visit the Report Schedules page.
                    </p>
                </body>
                </html>
                """.formatted(
                schedule.getName(),
                schedule.getReportId(),
                LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
                schedule.getFormat()
        );
    }

    private void sendEmail(String to, String subject, String htmlContent) throws MessagingException {
        if (mailSender == null) {
            log.warn("JavaMailSender not configured — email skipped for recipient {}", to);
            return;
        }
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlContent, true);
        helper.setFrom("noreply@auraboot.com");
        mailSender.send(message);
    }
}
