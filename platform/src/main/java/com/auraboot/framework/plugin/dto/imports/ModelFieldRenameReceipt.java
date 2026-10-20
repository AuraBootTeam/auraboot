package com.auraboot.framework.plugin.dto.imports;

/** Lossless, tenant-scoped field migration result. Contains no business values. */
public record ModelFieldRenameReceipt(String modelCode, String modelPid, String fromField, String toField,
                                     String sourceDefinitionSha256, String targetDefinitionSha256,
                                     long copiedRows, long retainedRows, boolean freshInstall) {}
