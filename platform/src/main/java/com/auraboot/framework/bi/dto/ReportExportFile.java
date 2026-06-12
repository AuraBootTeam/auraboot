package com.auraboot.framework.bi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Rendered report export artifact.
 */
@Data
@AllArgsConstructor
public class ReportExportFile {

    private byte[] bytes;

    private String filename;

    private String contentType;
}
