package com.auraboot.framework.bi;

import com.auraboot.framework.bi.dao.entity.ReportSchedule;
import com.auraboot.framework.bi.service.impl.ReportDeliveryServiceImpl;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReportDeliveryService.
 * Uses Mockito mocks for JavaMailSender as per project constraints.
 */
@ExtendWith(MockitoExtension.class)
class ReportDeliveryServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private MimeMessage mimeMessage;

    @InjectMocks
    private ReportDeliveryServiceImpl reportDeliveryService;

    private ReportSchedule schedule;

    @BeforeEach
    void setUp() {
        schedule = new ReportSchedule();
        schedule.setId(1L);
        schedule.setName("Weekly Report");
        schedule.setReportId("report-001");
        schedule.setFormat("pdf");
        schedule.setRecipients(List.of("user1@test.com", "user2@test.com"));
        schedule.setSubjectTemplate("Report: ${reportName} - ${date}");
    }

    @Test
    void generateAndSend_sendsToAllRecipients() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(mailSender).send(any(MimeMessage.class));

        reportDeliveryService.generateAndSend(schedule);

        // Should send 2 emails (one per recipient)
        verify(mailSender, times(2)).send(any(MimeMessage.class));
        verify(mailSender, times(2)).createMimeMessage();
    }

    @Test
    void generateAndSend_noRecipients_skips() {
        schedule.setRecipients(List.of());

        reportDeliveryService.generateAndSend(schedule);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void generateAndSend_nullRecipients_skips() {
        schedule.setRecipients(null);

        reportDeliveryService.generateAndSend(schedule);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void generateAndSend_mailFailure_throwsRuntimeException() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new RuntimeException("SMTP error")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> reportDeliveryService.generateAndSend(schedule))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to deliver report");
    }

    @Test
    void generateAndSend_defaultSubjectTemplate_usesDefaultWhenNull() throws Exception {
        schedule.setSubjectTemplate(null);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(mailSender).send(any(MimeMessage.class));

        // Should not throw - uses default template
        reportDeliveryService.generateAndSend(schedule);

        verify(mailSender, times(2)).send(any(MimeMessage.class));
    }
}
