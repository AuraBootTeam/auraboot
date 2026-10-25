package com.auraboot.framework.agent.profile;

import com.auraboot.framework.agent.profile.ProfileProjector.ProjectionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ProfileProjector} (PR-75). */
@DisplayName("ProfileProjector (PR-75)")
class ProfileProjectorTest {

    @Test
    @DisplayName("empty inputs → null persona, empty preferences/habits/expertise, null boundaries/language")
    void empty() {
        ProjectionResult r = ProfileProjector.project(List.of(), List.of());
        assertThat(r.persona()).isNull();
        assertThat(r.preferences()).isEmpty();
        assertThat(r.habits()).isEmpty();
        assertThat(r.expertise()).isEmpty();
        assertThat(r.boundaries()).isNull();
        assertThat(r.language()).isNull();
    }

    @Test
    @DisplayName("persona picks category='profile' and high-importance memories; cites source pids")
    void personaProjected() {
        List<Map<String, Object>> memories = List.of(
                memory("M1", "profile", "Engineer", "works on SaaS", 7, false),
                memory("M2", "general", "nothing", "x", 3, false),
                memory("M3", "general", "Tenant admin role", "admin", 9, false)
        );
        ProjectionResult r = ProfileProjector.project(memories, List.of());
        assertThat(r.persona()).isNotNull();
        assertThat(r.persona().sourceMemoryPids()).contains("M1", "M3");
        assertThat(r.persona().sourceMemoryPids()).doesNotContain("M2");
        assertThat(r.persona().text()).contains("2 profile memories");
        assertThat(r.persona().confidence()).isBetween(0.5, 1.0);
    }

    @Test
    @DisplayName("communication_style preference dominant bucket")
    void preferencesCommStyle() {
        List<Map<String, Object>> memories = List.of(
                memory("M1", "general", "answer", "please keep it concise and bullet format", 6, true),
                memory("M2", "general", "style", "brief, 简明 answers preferred", 6, true),
                memory("M3", "general", "code", "I like code examples", 5, false)
        );
        ProjectionResult r = ProfileProjector.project(memories, List.of());
        assertThat(r.preferences())
                .extracting(ProfileProjector.PreferenceCandidate::field)
                .contains("communication_style");
        var style = r.preferences().stream()
                .filter(p -> p.field().equals("communication_style"))
                .findFirst().orElseThrow();
        assertThat(style.text()).isEqualTo("concise bullet points");
        assertThat(style.sourceMemoryPids()).contains("M1", "M2");
    }

    @Test
    @DisplayName("domain_vocabulary from distinct action target_model names")
    void preferencesDomainVocab() {
        List<Map<String, Object>> actions = List.of(
                action("create", "order", Instant.now()),
                action("update", "order", Instant.now()),
                action("create", "invoice", Instant.now())
        );
        ProjectionResult r = ProfileProjector.project(List.of(), actions);
        var dv = r.preferences().stream()
                .filter(p -> p.field().equals("domain_vocabulary"))
                .findFirst().orElseThrow();
        assertThat(dv.text()).isEqualTo("invoice, order");
    }

    @Test
    @DisplayName("working_hours detected from modal action hour (Asia/Shanghai)")
    void preferencesWorkingHours() {
        // Build 3 actions at 10:30 UTC = 18:30 Asia/Shanghai
        Instant base = Instant.parse("2026-04-10T10:30:00Z");
        List<Map<String, Object>> actions = List.of(
                action("create", "order", base),
                action("update", "order", base.plusSeconds(60)),
                action("create", "order", base.plusSeconds(120))
        );
        ProjectionResult r = ProfileProjector.project(List.of(), actions);
        var wh = r.preferences().stream()
                .filter(p -> p.field().equals("working_hours"))
                .findFirst().orElseThrow();
        assertThat(wh.text()).contains("Asia/Shanghai");
        assertThat(wh.text()).contains("16:00").contains("21:00"); // peak=18, window [16, 21)
    }

    @Test
    @DisplayName("habits requires ≥ 3 occurrences within 30d")
    void habitsThreshold() {
        Instant now = Instant.now();
        List<Map<String, Object>> actionsUnder = List.of(
                action("create", "order", now.minusSeconds(1_000)),
                action("create", "order", now.minusSeconds(2_000))
        );
        assertThat(ProfileProjector.project(List.of(), actionsUnder).habits()).isEmpty();

        List<Map<String, Object>> actionsOver = List.of(
                action("create", "order", now.minusSeconds(1_000)),
                action("create", "order", now.minusSeconds(2_000)),
                action("create", "order", now.minusSeconds(3_000))
        );
        var habits = ProfileProjector.project(List.of(), actionsOver).habits();
        assertThat(habits).hasSize(1);
        assertThat(habits.get(0).pattern()).isEqualTo("create order");
        assertThat(habits.get(0).sourceActionCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("expertise derived from target_model with evidence≥3")
    void expertise() {
        Instant now = Instant.now();
        List<Map<String, Object>> actions = new ArrayList<>();
        for (int i = 0; i < 5; i++) actions.add(action("create", "invoice", now));
        for (int i = 0; i < 2; i++) actions.add(action("update", "invoice", now));
        for (int i = 0; i < 2; i++) actions.add(action("create", "order", now));  // below threshold
        var exp = ProfileProjector.project(List.of(), actions).expertise();
        assertThat(exp).hasSize(1);
        assertThat(exp.get(0).name()).isEqualTo("invoice");
        assertThat(exp.get(0).evidenceCount()).isEqualTo(7);
        assertThat(exp.get(0).confidence()).isBetween(0.5, 1.0);
    }

    @Test
    @DisplayName("boundaries picks category='boundary' or importance=10")
    void boundaries() {
        List<Map<String, Object>> memories = List.of(
                memory("B1", "boundary", "Never auto-send email", "x", 8, false),
                memory("B2", "general", "Strict rule", "y", 10, false),
                memory("B3", "general", "nothing", "z", 5, false)
        );
        ProjectionResult r = ProfileProjector.project(memories, List.of());
        assertThat(r.boundaries()).isNotNull();
        assertThat(r.boundaries().sourceMemoryPids()).contains("B1", "B2");
        assertThat(r.boundaries().sourceMemoryPids()).doesNotContain("B3");
        assertThat(r.boundaries().confidence()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("language majority vote: HAN vs Latin characters")
    void languageDetection() {
        List<Map<String, Object>> zh = List.of(
                memory("M", "general", "中文记忆", "这段记忆全是中文字符", 5, false));
        assertThat(ProfileProjector.project(zh, List.of()).language()).isEqualTo("zh-CN");

        List<Map<String, Object>> en = List.of(
                memory("M", "general", "english", "this memory is entirely english", 5, false));
        assertThat(ProfileProjector.project(en, List.of()).language()).isEqualTo("en-US");
    }

    @Test
    @DisplayName("single memory still produces persona when importance is high")
    void singleHighImportanceMemory() {
        List<Map<String, Object>> memories = List.of(
                memory("M1", "profile", "Pragmatic engineer", "x", 9, false));
        var r = ProfileProjector.project(memories, List.of());
        assertThat(r.persona()).isNotNull();
        assertThat(r.persona().sourceMemoryPids()).containsExactly("M1");
    }

    // helpers
    private static Map<String, Object> memory(String pid, String category, String title,
                                              String content, int importance, boolean shareable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", pid);
        m.put("category", category);
        m.put("memory_title", title);
        m.put("memory_content", content);
        m.put("importance", importance);
        m.put("shareable", shareable);
        return m;
    }

    private static Map<String, Object> action(String type, String model, Instant when) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("action_type", type);
        a.put("target_model", model);
        a.put("created_at", when);
        return a;
    }
}
