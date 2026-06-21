package com.auraboot.framework.behavior.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

/** Standard chart-api shape so the frontend chart api branch can read result.data.records. */
@Data
@AllArgsConstructor
public class BehaviorAnalyticsRecords<T> {
    private List<T> records;
}
