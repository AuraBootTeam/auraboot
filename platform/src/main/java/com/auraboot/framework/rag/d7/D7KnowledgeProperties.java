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
}
