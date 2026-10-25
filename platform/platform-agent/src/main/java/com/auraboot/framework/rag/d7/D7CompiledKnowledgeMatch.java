package com.auraboot.framework.rag.d7;

import lombok.Value;

@Value
public class D7CompiledKnowledgeMatch {

    D7CompiledKnowledgePage page;
    double score;
    boolean requiresRawEvidence;
}
