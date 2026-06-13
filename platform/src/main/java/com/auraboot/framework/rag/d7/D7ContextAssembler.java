package com.auraboot.framework.rag.d7;

import com.auraboot.framework.rag.util.VectorUtils;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class D7ContextAssembler {


    /**
     * Render RRF-fused compiled pages and raw chunks in fused order under a
     * token budget (G4+G5). Blocks beyond the budget are dropped; the first
     * item is always kept so retrieval never silently yields nothing.
     */
    public String buildFusedContext(List<D7RagFusion.FusedItem> items, int maxTokens) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        int budget = maxTokens > 0 ? maxTokens : Integer.MAX_VALUE;
        StringBuilder sb = new StringBuilder("\n\n## Retrieved Knowledge\n");
        sb.append("Reviewed compiled pages and raw chunks, ranked by relevance. ");
        sb.append("Prefer compiled pages; cite sources using the listed paths or ");
        sb.append("[Source: docName, Chunk N] format.\n\n");
        int used = VectorUtils.estimateTokens(sb.toString());
        int emitted = 0;
        int dropped = 0;

        for (D7RagFusion.FusedItem item : items) {
            String block = item.isCompiled() ? renderCompiledBlock(item.compiled()) : renderRawBlock(item.raw());
            int blockTokens = VectorUtils.estimateTokens(block);
            if (emitted > 0 && used + blockTokens > budget) {
                dropped++;
                continue;
            }
            sb.append(block);
            used += blockTokens;
            emitted++;
        }
        if (dropped > 0) {
            sb.append("(").append(dropped).append(" lower-ranked result(s) omitted for context budget)\n");
        }
        return sb.toString();
    }

    private String renderCompiledBlock(D7CompiledKnowledgeMatch match) {
        StringBuilder sb = new StringBuilder();
        D7CompiledKnowledgePage page = match.getPage();
        sb.append("### [Compiled: ").append(nonBlank(page.getTitle(), page.getId())).append("]\n");
        sb.append("- Page ID: `").append(page.getId()).append("`\n");
        sb.append("- Type: `").append(nonBlank(page.getType(), "unknown")).append("`\n");
        sb.append("- Stale status: `").append(nonBlank(page.getStaleStatus(), "unknown")).append("`\n");
        sb.append("- Requires raw evidence: ").append(match.isRequiresRawEvidence()).append("\n");
        appendSources(sb, page.getSourceRefs());
        appendSection(sb, "Summary", page.getSummary());
        appendSection(sb, "Body", page.getBody());
        sb.append("\n---\n\n");
        return sb.toString();
    }

    private String renderRawBlock(com.auraboot.framework.rag.dto.RetrievalResult r) {
        return "### [Source: " + r.getDocName() + ", Chunk " + r.getChunkIndex() + "]\n"
                + r.getContent() + "\n\n---\n\n";
    }

    private void appendSources(StringBuilder sb, List<D7SourceRef> sourceRefs) {
        if (sourceRefs == null || sourceRefs.isEmpty()) {
            return;
        }
        sb.append("- Sources: ");
        for (int i = 0; i < sourceRefs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("[Source: ").append(sourceRefs.get(i).getPath()).append("]");
        }
        sb.append("\n");
    }

    private void appendSection(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("\n").append(label).append(":\n").append(value).append("\n");
        }
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
