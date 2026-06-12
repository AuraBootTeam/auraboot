package com.auraboot.framework.bi.service;

import com.auraboot.framework.bi.dto.ReportExportFile;
import com.auraboot.framework.bi.dto.ReportExportRequest;

/**
 * Renders Report Designer DSL into downloadable artifacts.
 */
public interface ReportExportService {

    ReportExportFile exportExcel(ReportExportRequest request);

    ReportExportFile exportPdf(ReportExportRequest request);

    ReportExportFile exportJson(ReportExportRequest request);
}
