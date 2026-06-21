package com.auraboot.framework.bi.service.impl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the Phase 3 WYSIWYG report renderer (Option A',
 * DDR-2026-06-21-report-export-rendering-source-of-truth).
 *
 * <p>Default is "off by absence": {@code enabled} is true but {@code command} is
 * empty, so {@link ReportRenderClient#renderPdf} returns null and PDF export
 * falls back to the legacy PDFBox text path. There is no behaviour change until
 * ops wires the Node renderer command (e.g. the bundled cli.js).
 */
@Component
@ConfigurationProperties(prefix = "auraboot.report-export.renderer")
public class ReportRenderProperties {

    /** Master switch for the WYSIWYG renderer. */
    private boolean enabled = true;

    /**
     * Command that runs the Node render CLI. The client appends
     * {@code --out <tempfile>} and writes the request JSON to stdin. Empty means
     * the renderer is unavailable and PDF export falls back to PDFBox.
     */
    private List<String> command = new ArrayList<>();

    /** Hard timeout for a single render invocation, in seconds. */
    private long timeoutSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getCommand() {
        return command;
    }

    public void setCommand(List<String> command) {
        this.command = command == null ? new ArrayList<>() : new ArrayList<>(command);
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
