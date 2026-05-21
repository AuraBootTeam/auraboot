package com.auraboot.framework.rag.d7;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class D7ContextAssembler {

    public String buildAuraBotContext(List<D7CompiledKnowledgeMatch> matches, String rawContext) {
        boolean hasMatches = matches != null && !matches.isEmpty();
        boolean hasRawContext = rawContext != null && !rawContext.isBlank();
        if (!hasMatches) {
            return hasRawContext ? rawContext : "";
        }

        StringBuilder sb = new StringBuilder("\n\n## Compiled Knowledge\n");
        sb.append("Use these reviewed knowledge pages before raw chunks. ");
        sb.append("Keep citations traceable to the listed source paths.\n\n");

        for (D7CompiledKnowledgeMatch match : matches) {
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
        }

        if (hasRawContext) {
            sb.append(rawContext);
        }
        return sb.toString();
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
