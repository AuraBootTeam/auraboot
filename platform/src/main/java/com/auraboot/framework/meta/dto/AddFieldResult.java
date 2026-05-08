package com.auraboot.framework.meta.dto;

import java.time.Instant;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AddFieldResult {
    private String fieldPid;
    private String storageCode;
    private String columnName;
    private String tableName;
    private String pgColumnType;
    private Instant addedAt;
}
