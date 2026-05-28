package com.auraboot.framework.semantic.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Response of {@code GET /api/semantic/lineage/{pid}}.
 *
 * <p>Returns both incoming (what depends on me) and outgoing (what I depend on)
 * edges relative to the queried node, enabling impact analysis on metric / model
 * changes.
 */
@Data
@NoArgsConstructor
public class SemanticLineageResponse {

    private String nodePid;
    private String nodeType;
    private List<Edge> incoming = new ArrayList<>();
    private List<Edge> outgoing = new ArrayList<>();

    @Data
    @NoArgsConstructor
    public static class Edge {
        private String srcPid;
        private String srcType;
        private String dstPid;
        private String dstType;
        private String refType;
    }
}
