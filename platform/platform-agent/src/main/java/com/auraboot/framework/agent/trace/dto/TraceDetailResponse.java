package com.auraboot.framework.agent.trace.dto;

import com.auraboot.framework.agent.trace.entity.AiTrace;
import com.auraboot.framework.agent.trace.entity.AiTraceSpan;
import lombok.Data;

import java.util.List;

@Data
public class TraceDetailResponse {
    private AiTrace trace;
    private List<AiTraceSpan> spans;
}
