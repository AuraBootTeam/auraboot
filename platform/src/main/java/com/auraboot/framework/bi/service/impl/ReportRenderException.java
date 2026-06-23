package com.auraboot.framework.bi.service.impl;

/**
 * Thrown when the Phase 3 WYSIWYG report renderer subprocess fails. Callers
 * (e.g. {@code ReportExportServiceImpl.exportPdf}) catch this and fall back to
 * the legacy PDFBox text export — the failure is logged, never silently
 * swallowed.
 */
public class ReportRenderException extends RuntimeException {

    public ReportRenderException(String message) {
        super(message);
    }

    public ReportRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
