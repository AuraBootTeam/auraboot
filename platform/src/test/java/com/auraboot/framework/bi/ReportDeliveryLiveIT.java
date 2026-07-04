package com.auraboot.framework.bi;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.dao.entity.ReportSchedule;
import com.auraboot.framework.bi.service.impl.ReportDeliveryServiceImpl;
import com.auraboot.framework.bi.service.impl.ReportExportServiceImpl;
import com.auraboot.framework.bi.service.impl.ReportRenderClient;
import com.auraboot.framework.bi.service.impl.ReportRenderProperties;
import com.auraboot.framework.bi.service.ReportStorageService;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.meta.service.impl.AuditTrailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Full-stack delivery golden (B7, DDR-2026-06-21): a scheduled report is delivered
 * as a PDF email attachment rendered through the REAL export + Node renderer +
 * Chromium chain. Proves "scheduled report PDF also shows real charts". DAOs and
 * the mail sender are mocked (no DB, no SMTP); guarded to skip when the local
 * renderer deps are absent.
 */
@ExtendWith(MockitoExtension.class)
class ReportDeliveryLiveIT {

    @Mock
    private PageSchemaMapper pageSchemaMapper;
    @Mock
    private DynamicDataService dynamicDataService;
    @Mock
    private NamedQueryService namedQueryService;
    @Mock
    private ReportStorageService reportStorageService;
    @Mock
    private AuditTrailService auditTrailService;
    @Mock
    private JavaMailSender mailSender;

    private static Path webAdmin() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path sibling = cwd.resolveSibling("web-admin");
        return Files.exists(sibling) ? sibling : cwd.resolve("web-admin");
    }

    private static Path tsx() {
        return webAdmin().resolve("node_modules/.bin/tsx");
    }

    private static Path cli() {
        return webAdmin().resolve("app/framework/smart/report-export/cli.ts");
    }

    static boolean rendererAvailable() {
        if (!Files.isExecutable(tsx()) || !Files.exists(cli())) {
            return false;
        }
        try {
            return new ProcessBuilder(tsx().toString(), "--version").start().waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @EnabledIf("rendererAvailable")
    void scheduledDelivery_attachesRealWysiwygPdf() throws Exception {
        ReportRenderProperties props = new ReportRenderProperties();
        props.setEnabled(true);
        props.setCommand(List.of(tsx().toString(), cli().toString()));
        props.setTimeoutSeconds(90);
        ReportRenderClient renderClient = new ReportRenderClient(new ObjectMapper(), props);

        ReportExportServiceImpl exportService = new ReportExportServiceImpl(
                pageSchemaMapper, new ObjectMapper(), dynamicDataService, namedQueryService,
                reportStorageService, auditTrailService, renderClient);

        ReportDeliveryServiceImpl deliveryService = new ReportDeliveryServiceImpl(exportService);
        ReflectionTestUtils.setField(deliveryService, "mailSender", mailSender);

        PageSchema page = new PageSchema();
        ExtensionBean extension = new ExtensionBean();
        extension.setDynamicProperty("reportDsl", chartReportDsl());
        page.setExtension(extension);
        when(pageSchemaMapper.selectByPid("rpt-deliver")).thenReturn(page);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));

        ReportSchedule schedule = new ReportSchedule();
        schedule.setId(1L);
        schedule.setName("Scheduled Live Report");
        schedule.setReportId("rpt-deliver");
        schedule.setFormat("pdf");
        schedule.setTenantId(7L);
        schedule.setCreatedBy(99L);
        schedule.setRecipients(List.of("ops@example.com"));

        deliveryService.generateAndSend(schedule);

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        org.mockito.Mockito.verify(mailSender).send(sent.capture());

        byte[] pdf = firstPdfAttachment(sent.getValue());
        assertThat(pdf).isNotNull();
        assertThat(pdf).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf))) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Scheduled Report Header"); // running header in the delivered PDF
            assertThat(text).contains("Revenue");
        }
    }

    private static byte[] firstPdfAttachment(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                byte[] found = firstPdfAttachment(mp.getBodyPart(i));
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        String name = part.getFileName();
        if (name != null && name.endsWith(".pdf")) {
            return part.getInputStream().readAllBytes();
        }
        return null;
    }

    private Map<String, Object> chartReportDsl() {
        Map<String, Object> dataSource = new LinkedHashMap<>();
        dataSource.put("type", "static");
        dataSource.put("data", List.of(
                Map.of("month", "Jan", "amount", 100),
                Map.of("month", "Feb", "amount", 140)));

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("blockType", "page-header");
        header.put("content", "Scheduled Report Header");

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("blockType", "chart");
        chart.put("title", "Revenue");
        chart.put("dataSource", "rev");
        chart.put("chartType", "bar");
        chart.put("categoryField", "month");
        chart.put("valueField", "amount");
        chart.put("aggregation", "sum");

        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("title", "Scheduled Live Export");
        dsl.put("dataSources", Map.of("rev", dataSource));
        dsl.put("body", List.of(header, chart));
        return dsl;
    }
}
