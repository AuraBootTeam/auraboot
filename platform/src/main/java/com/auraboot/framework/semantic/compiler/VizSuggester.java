package com.auraboot.framework.semantic.compiler;

import com.auraboot.framework.semantic.dto.DimensionDTO;

import java.util.List;

/**
 * Suggests a default chart type for a compiled query, based on dimension types
 * and metric count. PRD 16 §10.2 (Dashboard widget defaults).
 *
 * <p>Heuristic (v0.1):
 * <ul>
 *   <li>0 dim, 1 metric → {@code kpi}</li>
 *   <li>any dim type=time → {@code line}</li>
 *   <li>1 dim, 1 metric → {@code bar}</li>
 *   <li>≥ 2 dims (no time) → {@code pivot}</li>
 *   <li>1 dim, ≥ 2 metrics → {@code bar} (grouped)</li>
 *   <li>0 dim, ≥ 2 metrics → {@code kpi} (multi)</li>
 * </ul>
 */
public final class VizSuggester {

    private VizSuggester() {}

    public static String suggest(List<DimensionDTO> resolvedDims, int metricCount) {
        int dimCount = resolvedDims == null ? 0 : resolvedDims.size();
        boolean hasTime = resolvedDims != null && resolvedDims.stream()
                .anyMatch(d -> "time".equalsIgnoreCase(d.getType()));
        if (hasTime) {
            return "line";
        }
        if (dimCount == 0) {
            return "kpi";
        }
        if (dimCount == 1) {
            return "bar";
        }
        return "pivot";
    }
}
