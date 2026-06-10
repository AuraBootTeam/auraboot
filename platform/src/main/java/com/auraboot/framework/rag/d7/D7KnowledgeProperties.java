package com.auraboot.framework.rag.d7;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "aurabot.d7")
public class D7KnowledgeProperties {

    private boolean enabled = false;
    private int maxCompiledPages = 3;
    private int rawTopK = 5;
    private String pageDirectory = "";
    private boolean traceEnabled = false;
    private String traceOutputPath = "";
    private String goldenQueryPath = "";

    /**
     * Token budget for the assembled RAG/D7 context injected into the system
     * prompt (G4). Estimated via VectorUtils.estimateTokens; items beyond the
     * budget are dropped (the first item is always kept).
     */
    private int contextMaxTokens = 3000;

    /** RRF constant k for D7+RAG fusion (G5/DDR-A). */
    private int rrfK = 60;

    /** Weight multiplier for compiled (reviewed) pages in RRF fusion (G5). */
    private double compiledRrfWeight = 1.5;
}
