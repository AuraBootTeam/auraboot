package com.auraboot.framework.plugin.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a plugin import operation.
 * Contains conflict details (for dry-run) or import summary (for actual import).
 */
@Data
public class PluginImportResult {

    private String pluginCode;

    private String status;

    private List<ConflictItem> conflicts = new ArrayList<>();

    private int schemasImported;

    private int permissionsImported;

    private int menusImported;

    private String errorMessage;

    private Long importLogId;
}
