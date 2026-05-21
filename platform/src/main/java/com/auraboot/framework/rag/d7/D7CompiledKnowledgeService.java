package com.auraboot.framework.rag.d7;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class D7CompiledKnowledgeService {

    private static final Set<String> EXCLUDED_STALE_STATUS = Set.of("orphan", "conflict");
    private static final Pattern HAN_SEQUENCE = Pattern.compile("\\p{IsHan}+");

    private final ObjectMapper objectMapper;
    private final D7KnowledgeProperties properties;

    public List<D7CompiledKnowledgeMatch> retrieve(Long tenantId, String query, int maxResults) {
        return rank(query, loadPages(), tenantId, maxResults);
    }

    public boolean hasRetrievablePages(Long tenantId) {
        return loadPages().stream().anyMatch(page -> isRetrievablePage(page, tenantId));
    }

    public List<D7CompiledKnowledgeMatch> rank(String query, List<D7CompiledKnowledgePage> pages,
                                               Long tenantId, int maxResults) {
        if (query == null || query.isBlank() || pages == null || pages.isEmpty()) {
            return List.of();
        }

        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        int limit = maxResults > 0 ? maxResults : properties.getMaxCompiledPages();
        return pages.stream()
                .filter(page -> isRetrievablePage(page, tenantId))
                .map(page -> toMatch(page, queryTerms))
                .filter(match -> match.getScore() > 0)
                .sorted(Comparator.comparing(D7CompiledKnowledgeMatch::isRequiresRawEvidence)
                        .thenComparing(D7CompiledKnowledgeMatch::getScore, Comparator.reverseOrder())
                        .thenComparing(match -> match.getPage().getId(), Comparator.nullsLast(String::compareTo)))
                .limit(limit)
                .toList();
    }

    public List<String> toRankedSourcePaths(List<D7CompiledKnowledgeMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        return matches.stream()
                .flatMap(match -> {
                    List<D7SourceRef> refs = match.getPage().getSourceRefs();
                    return refs == null ? java.util.stream.Stream.<D7SourceRef>empty() : refs.stream();
                })
                .map(D7SourceRef::getPath)
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
    }

    private D7CompiledKnowledgeMatch toMatch(D7CompiledKnowledgePage page, Set<String> queryTerms) {
        String searchable = String.join(" ",
                safe(page.getId()),
                safe(page.getType()),
                safe(page.getTitle()),
                safe(page.getSummary()),
                safe(page.getBody())).toLowerCase(Locale.ROOT);

        long hits = queryTerms.stream().filter(searchable::contains).count();
        double score = (double) hits / queryTerms.size();
        boolean requiresRawEvidence = "stale".equalsIgnoreCase(safe(page.getStaleStatus()));
        if (requiresRawEvidence) {
            score *= 0.25;
        }
        return new D7CompiledKnowledgeMatch(page, score, requiresRawEvidence);
    }

    private boolean isRetrievablePage(D7CompiledKnowledgePage page, Long tenantId) {
        if (page == null || !"published".equalsIgnoreCase(safe(page.getStatus()))) {
            return false;
        }
        String staleStatus = safe(page.getStaleStatus()).toLowerCase(Locale.ROOT);
        if (EXCLUDED_STALE_STATUS.contains(staleStatus)) {
            return false;
        }
        return isTenantVisible(page, tenantId);
    }

    private boolean isTenantVisible(D7CompiledKnowledgePage page, Long tenantId) {
        String visibility = safe(page.getVisibility()).toLowerCase(Locale.ROOT);
        String tenantScope = safe(page.getTenantScope()).toLowerCase(Locale.ROOT);
        if ("tenant".equals(visibility)) {
            return tenantId != null && tenantScope.equals(String.valueOf(tenantId));
        }
        return tenantScope.isBlank()
                || "global".equals(tenantScope)
                || "system".equals(tenantScope)
                || (tenantId != null && tenantScope.equals(String.valueOf(tenantId)));
    }

    private List<D7CompiledKnowledgePage> loadPages() {
        String pageDirectory = properties.getPageDirectory();
        if (pageDirectory == null || pageDirectory.isBlank()) {
            return List.of();
        }

        Path dir = Path.of(pageDirectory);
        if (!dir.isAbsolute()) {
            dir = Path.of(System.getProperty("user.dir")).resolve(dir).normalize();
        }
        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        try (var paths = Files.list(dir)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(this::readPage)
                    .filter(page -> page != null)
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to read D7 compiled knowledge directory {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    private D7CompiledKnowledgePage readPage(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), D7CompiledKnowledgePage.class);
        } catch (IOException e) {
            log.warn("Failed to read D7 compiled knowledge page {}: {}", path, e.getMessage());
            return null;
        }
    }

    private Set<String> terms(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String term : value.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}_]+")) {
            if (term.length() > 1) {
                result.add(term);
            }
        }
        Matcher matcher = HAN_SEQUENCE.matcher(value);
        while (matcher.find()) {
            String text = matcher.group();
            for (int i = 0; i < text.length() - 1; i++) {
                result.add(text.substring(i, i + 2));
            }
        }
        return result;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
