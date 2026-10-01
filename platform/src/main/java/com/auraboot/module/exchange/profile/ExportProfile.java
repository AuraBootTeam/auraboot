package com.auraboot.module.exchange.profile;

import com.auraboot.framework.meta.dto.DataExportRequest;
import com.auraboot.framework.meta.dto.DynamicDataExportRequest;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/** Server-owned export capability resolved from {@code model.extension.exportPolicy}. */
@Data
@Builder
public class ExportProfile {
    private String code;
    private String modelCode;
    private boolean enabled;
    private Set<DynamicDataExportRequest.Scope> scopes;
    private Set<DataExportRequest.ExportFormat> formats;
    /** Empty means the profile delegates the final whitelist to authorized model metadata. */
    @Builder.Default
    private List<String> fields = List.of();
}
