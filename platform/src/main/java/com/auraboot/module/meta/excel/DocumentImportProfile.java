package com.auraboot.module.meta.excel;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Declarative, command-bound contract for a two-sheet aggregate import. */
@Data
@Builder
public class DocumentImportProfile {
    private String code;
    private String modelCode;
    private String command;
    private String headerSheet;
    private String lineSheet;
    private String groupBy;
    private String lineGroupField;
    private String lineModelCode;
    private String linesPayloadField;
    private List<String> headerFields;
    private List<String> lineFields;
    @Builder.Default
    private boolean requireLines = true;
}
