package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Encapsulates all information needed to record a data change.
 *
 * @since 5.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangeRecord {

    private String modelCode;
    private String recordId;
    private String operation;       // CREATE / UPDATE / DELETE
    private Long changedBy;
    private String commandCode;
    private String clientRequestId;
    private List<FieldChange> changes;
    private Map<String, Object> snapshotBefore;
    private Map<String, Object> snapshotAfter;
}
