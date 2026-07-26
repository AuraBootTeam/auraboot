package com.auraboot.framework.chatbi.v2.lexer;

import com.auraboot.framework.chatbi.v2.dto.SearchToken;
import com.auraboot.framework.chatbi.v2.dto.TokenType;
import com.auraboot.framework.chatbi.v2.provider.IntentResult;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Catalog-bound lexer for ChatBI v2.
 *
 * <p>LLM tokens are treated as untrusted hints: metric and dimension codes are
 * resolved against the current semantic catalog and unknown codes are dropped.
 * When no provider produced usable tokens, a deliberately small deterministic
 * parser matches explicit catalog codes/labels plus bounded time-range, grain
 * and Top-N phrases. It never invents fields or SQL.
 */
@Slf4j
@Component
public class DefaultTokenLexer implements TokenLexer {

    private static final Set<String> TIME_PRESETS = Set.of(
            "ytd", "mtd", "qtd", "last_7_days", "last_30_days", "last_month", "custom");
    private static final Set<String> TIME_GRAINS = Set.of(
            "day", "week", "month", "quarter", "year");
    private static final Pattern TOP_N = Pattern.compile(
            "(?i)\\btop\\s*(\\d{1,4})\\b|前\\s*(\\d{1,4})\\s*(?:名|个|条)");

    private static final List<PhraseValue> TIME_RANGE_PHRASES = List.of(
            new PhraseValue("最近30天", "last_30_days"),
            new PhraseValue("过去30天", "last_30_days"),
            new PhraseValue("last 30 days", "last_30_days"),
            new PhraseValue("最近7天", "last_7_days"),
            new PhraseValue("过去7天", "last_7_days"),
            new PhraseValue("last 7 days", "last_7_days"),
            new PhraseValue("上个月", "last_month"),
            new PhraseValue("上月", "last_month"),
            new PhraseValue("last month", "last_month"),
            new PhraseValue("本季度", "qtd"),
            new PhraseValue("this quarter", "qtd"),
            new PhraseValue("quarter to date", "qtd"),
            new PhraseValue("本月", "mtd"),
            new PhraseValue("this month", "mtd"),
            new PhraseValue("month to date", "mtd"),
            new PhraseValue("今年", "ytd"),
            new PhraseValue("本年", "ytd"),
            new PhraseValue("this year", "ytd"),
            new PhraseValue("year to date", "ytd"));

    private static final List<PhraseValue> GRAIN_PHRASES = List.of(
            new PhraseValue("按季度", "quarter"),
            new PhraseValue("by quarter", "quarter"),
            new PhraseValue("quarterly", "quarter"),
            new PhraseValue("按月份", "month"),
            new PhraseValue("按月", "month"),
            new PhraseValue("by month", "month"),
            new PhraseValue("monthly", "month"),
            new PhraseValue("按星期", "week"),
            new PhraseValue("按周", "week"),
            new PhraseValue("by week", "week"),
            new PhraseValue("weekly", "week"),
            new PhraseValue("按日期", "day"),
            new PhraseValue("按天", "day"),
            new PhraseValue("按日", "day"),
            new PhraseValue("by day", "day"),
            new PhraseValue("daily", "day"),
            new PhraseValue("按年份", "year"),
            new PhraseValue("按年", "year"),
            new PhraseValue("by year", "year"),
            new PhraseValue("yearly", "year"));

    @Override
    public List<SearchToken> lex(String nlQuery,
                                 SemanticMetaResponse catalog,
                                 IntentResult llmHint) {
        try {
            CatalogIndex index = CatalogIndex.from(catalog);
            if (index.models().isEmpty()) {
                return List.of();
            }

            List<SearchToken> validated = validateHint(llmHint, index);
            if (containsMetric(validated)) {
                return enrichValidatedHint(validated, lexFromCatalog(nlQuery, index));
            }

            // Preserve a provider's explicit clarification decision. The answer
            // service will route it to DisambiguationService instead of guessing.
            if (llmHint != null
                    && llmHint.needsClarification()
                    && llmHint.disambiguation() != null) {
                return validated;
            }
            return lexFromCatalog(nlQuery, index);
        } catch (RuntimeException e) {
            // TokenLexer is a never-throw boundary.
            log.warn("Catalog token lexing failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<SearchToken> validateHint(IntentResult hint, CatalogIndex index) {
        if (hint == null || hint.tokens() == null || hint.tokens().isEmpty()) {
            return List.of();
        }
        List<SearchToken> validated = new ArrayList<>();
        for (SearchToken token : hint.tokens()) {
            if (token == null || token.type() == null) {
                continue;
            }
            switch (token.type()) {
                case METRIC -> index.resolveMetric(token.resolvedCode())
                        .map(code -> copyWithCode(token, code, validated.size()))
                        .ifPresent(validated::add);
                case DIMENSION -> index.resolveDimension(token.resolvedCode())
                        .filter(dimension -> validGrain(token.dateBucket(), dimension.timeGrains()))
                        .map(dimension -> copyWithCode(
                                token, dimension.qualifiedCode(), validated.size()))
                        .ifPresent(validated::add);
                case TIME_RANGE -> {
                    if (TIME_PRESETS.contains(token.resolvedCode())) {
                        validated.add(copyWithCode(
                                token, token.resolvedCode(), validated.size()));
                    }
                }
                case TOP_N -> {
                    Integer limit = positiveLimit(token.value());
                    if (limit != null) {
                        validated.add(SearchToken.topN(
                                limit, token.rawText(), validated.size()));
                    }
                }
                case KEYWORD, OPERATOR, VALUE, AGGREGATION, DATE_BUCKET, COLUMN -> {
                    // These have no independent compiler slot. Any semantic effect
                    // must already be folded into a verified metric/dimension token.
                }
            }
        }
        return List.copyOf(validated);
    }

    private SearchToken copyWithCode(SearchToken token, String code, int position) {
        return new SearchToken(
                token.type(), token.rawText(), code, token.operator(), token.value(),
                position, token.dateBucket(), token.aggregation());
    }

    private List<SearchToken> enrichValidatedHint(List<SearchToken> validated,
                                                   List<SearchToken> deterministic) {
        List<SearchToken> merged = new ArrayList<>(validated);
        Set<String> keys = validated.stream()
                .map(this::tokenKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (SearchToken token : deterministic) {
            // The provider remains authoritative for metrics. Deterministic
            // parsing may only fill dimensions/range/grain/limit around it.
            if (token.type() == TokenType.METRIC) {
                continue;
            }
            String key = tokenKey(token);
            if (keys.add(key)) {
                merged.add(copyWithPosition(token, merged.size()));
            }
        }
        return List.copyOf(merged);
    }

    private SearchToken copyWithPosition(SearchToken token, int position) {
        return new SearchToken(
                token.type(), token.rawText(), token.resolvedCode(),
                token.operator(), token.value(), position,
                token.dateBucket(), token.aggregation());
    }

    private String tokenKey(SearchToken token) {
        return token.type() + "|" + token.resolvedCode() + "|" + token.value()
                + "|" + token.dateBucket();
    }

    private List<SearchToken> lexFromCatalog(String nlQuery, CatalogIndex index) {
        String normalizedQuery = normalize(nlQuery);
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }

        List<ElementMatch<MetricRef>> metricMatches = index.metrics().stream()
                .map(metric -> bestMatch(normalizedQuery, metric, MetricRef::aliases))
                .flatMap(java.util.Optional::stream)
                .toList();
        if (metricMatches.isEmpty()) {
            return List.of();
        }

        Map<String, List<ElementMatch<MetricRef>>> byModel = metricMatches.stream()
                .collect(Collectors.groupingBy(match -> match.element().modelCode()));
        int bestScore = byModel.values().stream()
                .mapToInt(this::metricGroupScore)
                .max()
                .orElse(0);
        List<Map.Entry<String, List<ElementMatch<MetricRef>>>> winningModels =
                byModel.entrySet().stream()
                        .filter(entry -> metricGroupScore(entry.getValue()) == bestScore)
                        .toList();
        if (winningModels.size() != 1) {
            // Same-strength matches across models are unsafe to combine.
            return List.of();
        }

        String modelCode = winningModels.get(0).getKey();
        List<ElementMatch<MetricRef>> selectedMetrics = winningModels.get(0).getValue()
                .stream()
                .sorted(Comparator
                        .comparingInt((ElementMatch<MetricRef> match) -> match.queryPosition())
                        .thenComparing(match -> match.element().code()))
                .toList();

        List<ElementMatch<DimensionRef>> selectedDimensions = index.dimensions().stream()
                .filter(dimension -> modelCode.equals(dimension.modelCode()))
                .map(dimension -> bestMatch(
                        normalizedQuery, dimension, DimensionRef::aliases))
                .flatMap(java.util.Optional::stream)
                .sorted(Comparator
                        .comparingInt((ElementMatch<DimensionRef> match) -> match.queryPosition())
                        .thenComparing(match -> match.element().code()))
                .toList();

        String grain = detectPhrase(normalizedQuery, GRAIN_PHRASES);
        String timeRange = detectPhrase(normalizedQuery, TIME_RANGE_PHRASES);
        List<SearchToken> tokens = new ArrayList<>();
        for (ElementMatch<MetricRef> match : selectedMetrics) {
            tokens.add(SearchToken.metric(
                    match.element().qualifiedCode(),
                    match.matchedAlias(),
                    tokens.size()));
        }

        boolean hasTimeDimension = false;
        for (ElementMatch<DimensionRef> match : selectedDimensions) {
            DimensionRef dimension = match.element();
            String bucket = null;
            if ("time".equalsIgnoreCase(dimension.type())) {
                hasTimeDimension = true;
                if (validGrain(grain, dimension.timeGrains())) {
                    bucket = grain;
                }
            }
            tokens.add(SearchToken.dimension(
                    dimension.qualifiedCode(), match.matchedAlias(), tokens.size(),
                    bucket, null, null));
        }

        if (grain != null && !hasTimeDimension) {
            index.primaryTime(modelCode)
                    .filter(dimension -> validGrain(grain, dimension.timeGrains()))
                    .ifPresent(dimension -> tokens.add(SearchToken.dimension(
                            dimension.qualifiedCode(), grain, tokens.size(),
                            grain, null, null)));
        }
        if (timeRange != null) {
            tokens.add(SearchToken.timeRange(timeRange, timeRange, tokens.size()));
        }
        Integer topN = detectTopN(nlQuery);
        if (topN != null) {
            tokens.add(SearchToken.topN(topN, String.valueOf(topN), tokens.size()));
        }
        return List.copyOf(tokens);
    }

    private int metricGroupScore(List<ElementMatch<MetricRef>> matches) {
        return matches.stream().mapToInt(ElementMatch::score).sum();
    }

    private <T> java.util.Optional<ElementMatch<T>> bestMatch(
            String normalizedQuery,
            T element,
            Function<T, Set<String>> aliases) {
        return aliases.apply(element).stream()
                .map(alias -> new AliasMatch(
                        alias, normalize(alias), normalizedQuery.indexOf(normalize(alias))))
                .filter(match -> usableAlias(match.normalizedAlias()))
                .filter(match -> match.position() >= 0)
                .max(Comparator
                        .comparingInt((AliasMatch match) -> match.normalizedAlias().length())
                        .thenComparing(match -> match.alias()))
                .map(match -> new ElementMatch<>(
                        element, match.alias(), match.position(),
                        match.normalizedAlias().length()));
    }

    private boolean usableAlias(String alias) {
        if (alias.isBlank()) {
            return false;
        }
        boolean asciiOnly = alias.chars().allMatch(ch -> ch < 128);
        return alias.codePointCount(0, alias.length()) >= (asciiOnly ? 3 : 2);
    }

    private String detectPhrase(String normalizedQuery, List<PhraseValue> phrases) {
        return phrases.stream()
                .filter(phrase -> normalizedQuery.contains(normalize(phrase.phrase())))
                .max(Comparator.comparingInt(phrase -> normalize(phrase.phrase()).length()))
                .map(PhraseValue::value)
                .orElse(null);
    }

    private Integer detectTopN(String query) {
        if (query == null) {
            return null;
        }
        Matcher matcher = TOP_N.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return positiveLimit(raw);
    }

    private static Integer positiveLimit(Object value) {
        try {
            int parsed = value instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(value));
            return parsed >= 1 && parsed <= 1000 ? parsed : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean validGrain(String grain, List<String> supported) {
        if (grain == null || grain.isBlank()) {
            return true;
        }
        return TIME_GRAINS.contains(grain)
                && supported != null
                && supported.contains(grain);
    }

    private static boolean containsMetric(List<SearchToken> tokens) {
        return tokens.stream().anyMatch(token -> token.type() == TokenType.METRIC);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private record PhraseValue(String phrase, String value) {}
    private record AliasMatch(String alias, String normalizedAlias, int position) {}
    private record ElementMatch<T>(
            T element, String matchedAlias, int queryPosition, int score) {}

    private record MetricRef(
            String modelCode, String code, Set<String> aliases) {
        String qualifiedCode() {
            return modelCode + "." + code;
        }
    }

    private record DimensionRef(
            String modelCode,
            String code,
            String type,
            List<String> timeGrains,
            boolean primaryTime,
            Set<String> aliases) {
        String qualifiedCode() {
            return modelCode + "." + code;
        }
    }

    private record CatalogIndex(
            List<SemanticMetaResponse.ModelMeta> models,
            List<MetricRef> metrics,
            List<DimensionRef> dimensions) {

        static CatalogIndex from(SemanticMetaResponse catalog) {
            List<SemanticMetaResponse.ModelMeta> models =
                    catalog == null || catalog.getModels() == null
                            ? List.of()
                            : catalog.getModels().stream()
                                    .filter(Objects::nonNull)
                                    .filter(model -> model.getCode() != null
                                            && !model.getCode().isBlank())
                                    .toList();
            List<MetricRef> metrics = new ArrayList<>();
            List<DimensionRef> dimensions = new ArrayList<>();
            for (SemanticMetaResponse.ModelMeta model : models) {
                if (model.getMetrics() != null) {
                    for (SemanticMetaResponse.MetricMeta metric : model.getMetrics()) {
                        if (metric != null && metric.getCode() != null) {
                            metrics.add(new MetricRef(
                                    model.getCode(), metric.getCode(),
                                    aliases(model.getCode(), metric.getCode(),
                                            metric.getLabel())));
                        }
                    }
                }
                if (model.getDimensions() != null) {
                    for (SemanticMetaResponse.DimensionMeta dimension
                            : model.getDimensions()) {
                        if (dimension != null && dimension.getCode() != null) {
                            dimensions.add(new DimensionRef(
                                    model.getCode(), dimension.getCode(),
                                    dimension.getType(),
                                    dimension.getTimeGrains() == null
                                            ? List.of() : List.copyOf(dimension.getTimeGrains()),
                                    dimension.isPrimaryTime(),
                                    aliases(model.getCode(), dimension.getCode(),
                                            dimension.getLabel())));
                        }
                    }
                }
            }
            return new CatalogIndex(
                    List.copyOf(models), List.copyOf(metrics), List.copyOf(dimensions));
        }

        java.util.Optional<String> resolveMetric(String code) {
            return resolve(
                    code, metrics, MetricRef::qualifiedCode, MetricRef::code);
        }

        java.util.Optional<DimensionRef> resolveDimension(String code) {
            if (code == null || code.isBlank()) {
                return java.util.Optional.empty();
            }
            List<DimensionRef> matches = dimensions.stream()
                    .filter(dimension -> code.equals(dimension.qualifiedCode())
                            || code.equals(dimension.code()))
                    .toList();
            return matches.size() == 1
                    ? java.util.Optional.of(matches.get(0))
                    : java.util.Optional.empty();
        }

        java.util.Optional<DimensionRef> primaryTime(String modelCode) {
            return dimensions.stream()
                    .filter(dimension -> modelCode.equals(dimension.modelCode()))
                    .filter(DimensionRef::primaryTime)
                    .findFirst();
        }

        private static <T> java.util.Optional<String> resolve(
                String code,
                List<T> elements,
                Function<T, String> qualified,
                Function<T, String> bare) {
            if (code == null || code.isBlank()) {
                return java.util.Optional.empty();
            }
            List<T> matches = elements.stream()
                    .filter(element -> code.equals(qualified.apply(element))
                            || code.equals(bare.apply(element)))
                    .toList();
            return matches.size() == 1
                    ? java.util.Optional.of(qualified.apply(matches.get(0)))
                    : java.util.Optional.empty();
        }

        private static Set<String> aliases(
                String modelCode, String code, Map<String, String> labels) {
            Set<String> aliases = new LinkedHashSet<>();
            aliases.add(modelCode + "." + code);
            aliases.add(code);
            aliases.add(code.replace('_', ' '));
            if (labels != null) {
                labels.values().stream()
                        .filter(Objects::nonNull)
                        .filter(label -> !label.isBlank())
                        .forEach(aliases::add);
            }
            return Set.copyOf(aliases);
        }
    }
}
