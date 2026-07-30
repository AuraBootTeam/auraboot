package com.auraboot.framework.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single retrieval result from hybrid search (vector + BM25).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResult {
    private String kbPid;
    private String documentPid;
    private String documentVersionPid;
    private String indexReleasePid;
    private String chunkPid;
    private String docName;
    private String kbName;
    private int chunkIndex;
    private String content;
    private double distance;
    private double similarity;
    @Builder.Default
    private double vectorScore = 0;
    @Builder.Default
    private double bm25Score = 0;
    @Builder.Default
    private double hybridScore = 0;
    @Builder.Default
    private double rerankScore = 0;
    private String citationLocator;
}
