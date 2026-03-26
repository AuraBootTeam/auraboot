package com.auraboot.framework.bpm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessStartRequest {
    private String modelCode;
    private Map<String, Object> businessData;
    private Map<String, Object> variables;
    private String saveStrategy;
}
