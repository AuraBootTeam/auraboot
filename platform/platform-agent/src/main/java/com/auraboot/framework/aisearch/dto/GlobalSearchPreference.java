package com.auraboot.framework.aisearch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Personal global-search selection. {@code enabledModelCodes} is ordered and
 * authoritative only when {@code configured} is true.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobalSearchPreference {

    private boolean configured;

    private List<String> enabledModelCodes;
}
