package com.auraboot.framework.aisearch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Request body for saving personal global-search candidates. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GlobalSearchPreferenceRequest {

    private List<String> modelCodes;
}
