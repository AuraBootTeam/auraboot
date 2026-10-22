package com.auraboot.framework.rag.d7;

import com.auraboot.framework.rag.dto.RetrievalResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reciprocal-rank fusion of D7 compiled pages and raw RAG chunks (G5, DDR-A
 * option A1). Each list contributes {@code weight / (k + rank)}; compiled
 * pages carry a weight bonus (reviewed knowledge outranks raw chunks at equal
 * rank) but a highly-ranked raw chunk can now beat a low-ranked compiled page
 * — previously compiled pages unconditionally preceded all chunks.
 */
public final class D7RagFusion {

    private D7RagFusion() {
    }

    /** One fused context item: exactly one of {@code compiled}/{@code raw} is non-null. */
    public record FusedItem(D7CompiledKnowledgeMatch compiled, RetrievalResult raw, double rrfScore) {
        public boolean isCompiled() {
            return compiled != null;
        }
    }

    public static List<FusedItem> fuse(List<D7CompiledKnowledgeMatch> compiledMatches,
                                       List<RetrievalResult> rawResults,
                                       int rrfK, double compiledWeight) {
        int k = rrfK > 0 ? rrfK : 60;
        double w = compiledWeight > 0 ? compiledWeight : 1.0;
        List<FusedItem> items = new ArrayList<>();
        if (compiledMatches != null) {
            for (int i = 0; i < compiledMatches.size(); i++) {
                items.add(new FusedItem(compiledMatches.get(i), null, w / (k + i + 1)));
            }
        }
        if (rawResults != null) {
            for (int i = 0; i < rawResults.size(); i++) {
                items.add(new FusedItem(null, rawResults.get(i), 1.0 / (k + i + 1)));
            }
        }
        items.sort(Comparator.comparingDouble(FusedItem::rrfScore).reversed());
        return items;
    }
}
