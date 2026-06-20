package com.auraboot.framework.behavior.dto;

import lombok.Data;

/** One event-name rollup row (M1 analysis: top events). */
@Data
public class BehaviorEventCount {
    private String eventName;
    private String eventCategory;
    private Long count;
}
