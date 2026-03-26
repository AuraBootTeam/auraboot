package com.auraboot.module.aps.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
@EqualsAndHashCode(of = "resourceId")
public class GanttRow {
    private Long resourceId;
    private String resourceName;
}
