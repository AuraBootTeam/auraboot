package com.auraboot.framework.bi;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.dao.entity.ReportSchedule;
import com.auraboot.framework.bi.dto.ReportExportFile;
import com.auraboot.framework.bi.dto.ReportExportRequest;
import com.auraboot.framework.bi.service.ReportExportService;
import com.auraboot.framework.bi.service.impl.ReportDeliveryServiceImpl;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReportDeliveryService. The delivery path renders the real report
 * via {@link ReportExportService} (B7) and attaches it; JavaMailSender is mocked.
 */
@ExtendWith(MockitoExtension.class)
class ReportDeliveryServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private ReportExportService reportExportService;

    private ReportDeliveryServiceImpl reportDeliveryService;

    private ReportSchedule schedule;

    @BeforeEach
    void setUp() {
        // The service uses constructor injection for the export service and field
        // injection (@Autowired(required=false)) for the optional mail sender, so wire
        // both explicitly (@InjectMocks would only fill the constructor arg).
        reportDeliveryService = new ReportDeliveryServiceImpl(reportExportService);
        ReflectionTestUtils.setField(reportDeliveryService, "mailSender", mailSender);

        schedule = new ReportSchedule();
        schedule.setId(1L);
        schedule.setName("Weekly Report");
        schedule.setReportId("report-001");
        schedule.setFormat("pdf");
        schedule.setRecipients(List.of("user1@test.com", "user2@test.com"));
        schedule.setSubjectTemplate("Report: ${reportName} - ${date}");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private void freshMimeMessages() {
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    }

    private static ReportExportFile pdf() {
        return new ReportExportFile("%PDF-1.4 scheduled".getBytes(), "Weekly Report.pdf", "application/pdf");
    }

    @Test
    void generateAndSend_sendsToAllRecipients() {
        freshMimeMessages();
        when(reportExportService.exportPdf(any())).thenReturn(pdf());

        reportDeliveryService.generateAndSend(schedule);

        // Renders the report once, then sends one email per recipient.
        verify(reportExportService, times(1)).exportPdf(any());
        verify(mailSender, times(2)).send(any(MimeMessage.class));
        verify(mailSender, times(2)).createMimeMessage();
    }

    @Test
    void generateAndSend_noRecipients_skips() {
        schedule.setRecipients(List.of());

        reportDeliveryService.generateAndSend(schedule);

        verify(reportExportService, never()).exportPdf(any());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void generateAndSend_nullRecipients_skips() {
        schedule.setRecipients(null);

        reportDeliveryService.generateAndSend(schedule);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void generateAndSend_mailFailure_throwsRuntimeException() {
        freshMimeMessages();
        when(reportExportService.exportPdf(any())).thenReturn(pdf());
        doThrow(new RuntimeException("SMTP error")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> reportDeliveryService.generateAndSend(schedule))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to deliver report");
    }

    @Test
    void generateAndSend_defaultSubjectTemplate_usesDefaultWhenNull() {
        schedule.setSubjectTemplate(null);
        freshMimeMessages();
        when(reportExportService.exportPdf(any())).thenReturn(pdf());

        reportDeliveryService.generateAndSend(schedule);

        verify(mailSender, times(2)).send(any(MimeMessage.class));
    }

    // ---------- B7: renders the real report and attaches it ----------

    @Test
    void generateAndSend_pdf_rendersViaExportPdf_andAttachesIt() throws Exception {
        freshMimeMessages();
        when(reportExportService.exportPdf(any())).thenReturn(pdf());
        schedule.setRecipients(List.of("user1@test.com"));

        reportDeliveryService.generateAndSend(schedule);

        ArgumentCaptor<ReportExportRequest> req = ArgumentCaptor.forClass(ReportExportRequest.class);
        verify(reportExportService).exportPdf(req.capture());
        assertThat(req.getValue().getReportPid()).isEqualTo("report-001");

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(attachmentFilenames(sent.getValue())).contains("Weekly Report.pdf");
    }

    @Test
    void generateAndSend_excelFormat_rendersViaExportExcel() {
        freshMimeMessages();
        when(reportExportService.exportExcel(any()))
                .thenReturn(new ReportExportFile("PK".getBytes(), "r.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        schedule.setFormat("excel");
        schedule.setRecipients(List.of("a@b.com"));

        reportDeliveryService.generateAndSend(schedule);

        verify(reportExportService).exportExcel(any());
        verify(reportExportService, never()).exportPdf(any());
    }

    @Test
    void renderReport_establishesScheduleTenantContext_whenNonePresent_andClears() {
        MetaContext.clear();
        AtomicReference<Long> seenTenant = new AtomicReference<>();
        freshMimeMessages();
        when(reportExportService.exportPdf(any())).thenAnswer(inv -> {
            seenTenant.set(MetaContext.getCurrentTenantId());
            return pdf();
        });
        schedule.setTenantId(42L);
        schedule.setCreatedBy(7L);
        schedule.setRecipients(List.of("a@b.com"));

        reportDeliveryService.generateAndSend(schedule);

        assertThat(seenTenant.get()).isEqualTo(42L); // schedule tenant set during render
        assertThat(MetaContext.exists()).isFalse();  // cleared after (no caller context to keep)
    }

    @Test
    void renderReport_preservesCallerContext_whenAlreadySet() {
        MetaContext.setContext(7L, 1L, "user-pid", "admin"); // e.g. an admin test-send
        AtomicReference<Long> seenTenant = new AtomicReference<>();
        freshMimeMessages();
        when(reportExportService.exportPdf(any())).thenAnswer(inv -> {
            seenTenant.set(MetaContext.getCurrentTenantId());
            return pdf();
        });
        schedule.setTenantId(42L);
        schedule.setRecipients(List.of("a@b.com"));

        reportDeliveryService.generateAndSend(schedule);

        assertThat(seenTenant.get()).isEqualTo(7L);                 // used caller's tenant, not schedule's 42
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(7L); // caller context never clobbered
    }

    private static List<String> attachmentFilenames(Part part) throws Exception {
        List<String> names = new ArrayList<>();
        Object content = part.getContent();
        if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                names.addAll(attachmentFilenames(mp.getBodyPart(i)));
            }
        } else if (part.getFileName() != null) {
            names.add(part.getFileName());
        }
        return names;
    }
}
