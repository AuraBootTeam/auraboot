package com.auraboot.framework.bi.controller;

import com.auraboot.framework.bi.dto.ReportExportFile;
import com.auraboot.framework.bi.dto.ReportExportRequest;
import com.auraboot.framework.bi.service.ReportExportService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * REST controller for Report Designer artifact exports.
 */
// Explicit bean name: the default "reportExportController" collides with the enterprise
// overlay's com.auraboot.framework.print.controller.ReportExportController during component
// scan (ConflictingBeanDefinitionException). Namespacing this BI controller avoids it.
@RestController("biReportExportController")
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Tag(name = "Report Export", description = "Report Designer artifact export API")
public class ReportExportController {

    private final ReportExportService reportExportService;

    @PostMapping("/export/excel")
    @Operation(summary = "Export report as Excel", description = "Renders a saved Report Designer DSL as an XLSX artifact")
    @RequirePermission(MetaPermission.REPORT_EXPORT_EXECUTE)
    public ResponseEntity<byte[]> exportExcel(@Valid @RequestBody ReportExportRequest request) {
        ReportExportFile file = reportExportService.exportExcel(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.getFilename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(file.getBytes());
    }

    @PostMapping("/export/pdf")
    @Operation(summary = "Export report as PDF", description = "Renders a saved Report Designer DSL as a PDF artifact")
    @RequirePermission(MetaPermission.REPORT_EXPORT_EXECUTE)
    public ResponseEntity<byte[]> exportPdf(@Valid @RequestBody ReportExportRequest request) {
        ReportExportFile file = reportExportService.exportPdf(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.getFilename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(file.getBytes());
    }

    @PostMapping("/export/json")
    @Operation(summary = "Export report as JSON", description = "Exports a saved Report Designer DSL and resolved data sets as JSON")
    @RequirePermission(MetaPermission.REPORT_EXPORT_EXECUTE)
    public ResponseEntity<byte[]> exportJson(@Valid @RequestBody ReportExportRequest request) {
        ReportExportFile file = reportExportService.exportJson(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.getFilename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(file.getBytes());
    }
}
