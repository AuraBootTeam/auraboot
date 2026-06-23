package com.auraboot.framework.behavior.dto;

import lombok.Data;

import java.util.List;

/** Batch payload for POST /api/collect (M1). */
@Data
public class CollectRequest {
    private List<BehaviorEventInput> events;
}
