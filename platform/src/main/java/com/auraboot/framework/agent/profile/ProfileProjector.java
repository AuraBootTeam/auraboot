package com.auraboot.framework.agent.profile;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * User Soul Profile candidate projector (PR-75, plan §5.1 step 3).
 *
 * <p>Pure-Java projection of structured inputs (user-scope memories +
 * recent actions) into per-field candidates. Phase 1 produces
 * deterministic, keyword/frequency-based projections; LLM rendering of
 * the prose fields is deferred to Phase 2. Keeping this class free of
 * Spring / database / network dependencies lets us unit-test every
 * branch and keeps derivation costs bounded.
 *
 * <p>Input row shapes match {@code AgentMemoryService.loadScopedByImportance}
 * (memories) and {@code ab_agent_action} (actions) — generic
 * {@code Map<String, Object>} so the projector stays thin.
 */
public final class ProfileProjector {

    // Category labels expected in ab_agent_memory.category.
    static final String CATEGORY_PROFILE = "profile";
    static final String CATEGORY_BOUNDARY = "boundary";

    // Communication-style keyword buckets. Order matters for the dominant bucket.
    private static final Map<String, List<String>> STYLE_KEYWORDS = new LinkedHashMap<>();
    static {
        STYLE_KEYWORDS.put("concise bullet points", List.of("简洁", "简明", "concise", "bullet", "要点", "brief"));
        STYLE_KEYWORDS.put("detailed explanations", List.of("详细", "展开", "detailed", "elaborate"));
        STYLE_KEYWORDS.put("code examples welcome", List.of("code", "代码", "example", "示例"));
        STYLE_KEYWORDS.put("formal tone",            List.of("formal", "正式", "严谨"));
        STYLE_KEYWORDS.put("casual tone",            List.of("casual", "轻松", "随意"));
    }

    private ProfileProjector() {}

    public record PersonaCandidate(
            String text,
            List<String> sourceMemoryPids,
            double confidence,
            long lastDerivedAt
    ) {}

    public record PreferenceCandidate(
            String field,
            String text,
            List<String> sourceMemoryPids,
            double confidence
    ) {}

    public record HabitPattern(
            String pattern,
            String frequency,
            int sourceActionCount,
            String lastSeen
    ) {}

    public record ExpertiseDomain(
            String name,
            double confidence,
            int evidenceCount
    ) {}

    public record BoundaryCandidate(
            String text,
            List<String> sourceMemoryPids,
            double confidence
    ) {}

    public record ProjectionResult(
            PersonaCandidate persona,
            List<PreferenceCandidate> preferences,
            List<HabitPattern> habits,
            List<ExpertiseDomain> expertise,
            BoundaryCandidate boundaries,
            String language
    ) {}

    /**
     * Project memories + actions into a candidate structure. Never returns
     * null; fields may be empty lists or null records if no evidence.
     */
    public static ProjectionResult project(
            List<Map<String, Object>> memories,
            List<Map<String, Object>> actions
    ) {
        List<Map<String, Object>> mem = memories == null ? List.of() : memories;
        List<Map<String, Object>> act = actions == null ? List.of() : actions;

        PersonaCandidate persona = projectPersona(mem);
        List<PreferenceCandidate> prefs = projectPreferences(mem, act);
        List<HabitPattern> habits = projectHabits(act);
        List<ExpertiseDomain> expertise = projectExpertise(act);
        BoundaryCandidate boundaries = projectBoundaries(mem);
        String language = detectLanguage(mem);

        return new ProjectionResult(persona, prefs, habits, expertise, boundaries, language);
    }

    // ---- Persona -----------------------------------------------------

    static PersonaCandidate projectPersona(List<Map<String, Object>> memories) {
        List<Map<String, Object>> profileMems = memories.stream()
                .filter(m -> CATEGORY_PROFILE.equalsIgnoreCase(asString(m.get("category")))
                        || asInt(m.get("importance"), 0) >= 8)
                .sorted(Comparator.<Map<String,Object>>comparingInt(m -> asInt(m.get("importance"), 0)).reversed())
                .limit(5)
                .toList();
        if (profileMems.isEmpty()) {
            return null;
        }
        List<String> pids = profileMems.stream()
                .map(m -> asString(m.get("pid")))
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toList());
        String topPhrases = profileMems.stream()
                .map(m -> asString(m.get("memory_title")))
                .filter(s -> s != null && !s.isBlank())
                .limit(3)
                .collect(Collectors.joining("; "));
        String text = "Based on " + profileMems.size() + " profile memor"
                + (profileMems.size() == 1 ? "y" : "ies")
                + (topPhrases.isBlank() ? "." : ": " + topPhrases);
        double avgImportance = profileMems.stream()
                .mapToInt(m -> asInt(m.get("importance"), 0)).average().orElse(0);
        double confidence = ProfileConfidenceScorer.forPersona(profileMems.size(), avgImportance);
        return new PersonaCandidate(text, pids, confidence, Instant.now().toEpochMilli());
    }

    // ---- Preferences -------------------------------------------------

    static List<PreferenceCandidate> projectPreferences(
            List<Map<String, Object>> memories, List<Map<String, Object>> actions) {
        List<PreferenceCandidate> out = new ArrayList<>();

        // communication_style — keyword frequency across shareable/user memories.
        Map<String, List<String>> styleHits = new LinkedHashMap<>();
        for (Map<String, Object> m : memories) {
            String content = lower(asString(m.get("memory_content")))
                    + " " + lower(asString(m.get("memory_title")));
            String pid = asString(m.get("pid"));
            for (var e : STYLE_KEYWORDS.entrySet()) {
                for (String kw : e.getValue()) {
                    if (content.contains(kw.toLowerCase(Locale.ROOT))) {
                        styleHits.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(pid);
                        break;
                    }
                }
            }
        }
        if (!styleHits.isEmpty()) {
            var top = styleHits.entrySet().stream()
                    .max(Comparator.comparingInt(e -> e.getValue().size()))
                    .orElseThrow();
            boolean shareable = memories.stream()
                    .anyMatch(m -> Boolean.TRUE.equals(m.get("shareable")));
            out.add(new PreferenceCandidate(
                    "communication_style",
                    top.getKey(),
                    top.getValue().stream().filter(s -> s != null && !s.isBlank()).distinct().toList(),
                    ProfileConfidenceScorer.forPreference(top.getValue().size(), shareable)
            ));
        }

        // domain_vocabulary — distinct target_model names from user actions.
        List<String> models = actions.stream()
                .map(a -> asString(a.get("target_model")))
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .sorted()
                .limit(10)
                .toList();
        if (!models.isEmpty()) {
            out.add(new PreferenceCandidate(
                    "domain_vocabulary",
                    String.join(", ", models),
                    List.of(),
                    ProfileConfidenceScorer.forPreference(Math.max(models.size(), 1), false)
            ));
        }

        // working_hours — modal hour bucket (Asia/Shanghai).
        int[] hourBuckets = new int[24];
        int hourSamples = 0;
        for (Map<String, Object> a : actions) {
            Object ts = a.get("created_at");
            Instant inst = asInstant(ts);
            if (inst == null) continue;
            int hour = ZonedDateTime.ofInstant(inst, ZoneId.of("Asia/Shanghai")).getHour();
            hourBuckets[hour]++;
            hourSamples++;
        }
        if (hourSamples >= 3) {
            int peak = 0;
            for (int i = 1; i < 24; i++) {
                if (hourBuckets[i] > hourBuckets[peak]) peak = i;
            }
            int start = Math.max(0, peak - 2);
            int end = Math.min(23, peak + 2);
            String text = String.format(Locale.ROOT,
                    "%02d:00-%02d:00 Asia/Shanghai", start, end + 1);
            out.add(new PreferenceCandidate(
                    "working_hours", text, List.of(),
                    ProfileConfidenceScorer.forPreference(hourSamples, false)
            ));
        }

        return out;
    }

    // ---- Habits ------------------------------------------------------

    static List<HabitPattern> projectHabits(List<Map<String, Object>> actions) {
        // Group by (action_type, target_model).
        Map<String, List<Instant>> groups = new LinkedHashMap<>();
        for (Map<String, Object> a : actions) {
            String type = asString(a.get("action_type"));
            String model = asString(a.get("target_model"));
            if (type == null || model == null) continue;
            Instant inst = asInstant(a.get("created_at"));
            groups.computeIfAbsent(type + "|" + model, k -> new ArrayList<>()).add(inst);
        }
        List<HabitPattern> patterns = new ArrayList<>();
        Instant now = Instant.now();
        Instant thirtyDaysAgo = now.minusSeconds(30L * 86_400L);
        for (var e : groups.entrySet()) {
            List<Instant> stamps = e.getValue().stream()
                    .filter(i -> i != null && i.isAfter(thirtyDaysAgo))
                    .toList();
            if (stamps.size() < 3) continue;
            String[] parts = e.getKey().split("\\|", 2);
            String pattern = parts[0] + " " + parts[1];
            int span = stamps.size();
            String frequency = span >= 20 ? "daily" : span >= 8 ? "weekly" : "monthly";
            Instant last = stamps.stream().max(Comparator.naturalOrder()).orElse(now);
            patterns.add(new HabitPattern(
                    pattern, frequency, stamps.size(), last.toString().substring(0, 10)
            ));
        }
        patterns.sort(Comparator.comparingInt(HabitPattern::sourceActionCount).reversed());
        return patterns;
    }

    // ---- Expertise ---------------------------------------------------

    static List<ExpertiseDomain> projectExpertise(List<Map<String, Object>> actions) {
        // Domain = target_model; evidence = count; confidence = f(unique action_types).
        Map<String, Set<String>> typesByModel = new LinkedHashMap<>();
        Map<String, Integer> countByModel = new LinkedHashMap<>();
        for (Map<String, Object> a : actions) {
            String model = asString(a.get("target_model"));
            if (model == null || model.isBlank()) continue;
            String type = asString(a.get("action_type"));
            typesByModel.computeIfAbsent(model, k -> new HashSet<>());
            if (type != null) typesByModel.get(model).add(type);
            countByModel.merge(model, 1, Integer::sum);
        }
        List<ExpertiseDomain> out = new ArrayList<>();
        for (var e : countByModel.entrySet()) {
            int count = e.getValue();
            if (count < 3) continue;
            int distinctTypes = typesByModel.get(e.getKey()).size();
            double confidence = Math.min(1.0, 0.5 + 0.1 * Math.min(distinctTypes, 5));
            out.add(new ExpertiseDomain(e.getKey(), confidence, count));
        }
        out.sort(Comparator.comparingInt(ExpertiseDomain::evidenceCount).reversed());
        return out;
    }

    // ---- Boundaries --------------------------------------------------

    static BoundaryCandidate projectBoundaries(List<Map<String, Object>> memories) {
        List<Map<String, Object>> matches = memories.stream()
                .filter(m -> CATEGORY_BOUNDARY.equalsIgnoreCase(asString(m.get("category")))
                        || asInt(m.get("importance"), 0) == 10)
                .toList();
        if (matches.isEmpty()) return null;
        List<String> pids = matches.stream()
                .map(m -> asString(m.get("pid")))
                .filter(s -> s != null && !s.isBlank())
                .toList();
        String text = matches.stream()
                .map(m -> asString(m.get("memory_title")))
                .filter(s -> s != null && !s.isBlank())
                .limit(3)
                .collect(Collectors.joining("; "));
        if (text.isBlank()) {
            text = "User-asserted boundary (see " + pids.size() + " source memor"
                    + (pids.size() == 1 ? "y" : "ies") + ")";
        }
        return new BoundaryCandidate(text, pids,
                ProfileConfidenceScorer.forBoundary(0));
    }

    // ---- Language detection ------------------------------------------

    static String detectLanguage(List<Map<String, Object>> memories) {
        int han = 0, latin = 0;
        for (Map<String, Object> m : memories) {
            String content = asString(m.get("memory_content"));
            if (content == null) continue;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                    han++;
                } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                    latin++;
                }
            }
        }
        if (han == 0 && latin == 0) return null;
        return han >= latin ? "zh-CN" : "en-US";
    }

    // ---- helpers -----------------------------------------------------

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static int asInt(Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return fallback;
        try { return Integer.parseInt(o.toString()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static Instant asInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof java.sql.Timestamp t) return t.toInstant();
        if (o instanceof java.util.Date d) return d.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (o instanceof java.time.ZonedDateTime zdt) return zdt.toInstant();
        if (o instanceof Number n) return Instant.ofEpochMilli(n.longValue());
        try { return Instant.parse(o.toString()); }
        catch (Exception e) { return null; }
    }
}
