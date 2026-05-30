package com.auraboot.framework.chatbi.v2.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.chatbi.v2.dto.SearchToken;
import com.auraboot.framework.chatbi.v2.dto.TokenType;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the system prompt + user message handed to the LLM, and parses the
 * structured JSON response back into {@link IntentResult}. PRD 17 §7.1-§7.3.
 *
 * <p>Three concerns isolated here so each LLM provider (Anthropic / OpenAI) is
 * a thin wire-format adapter and doesn't redefine the prompt:
 *
 * <ul>
 *   <li>{@link #buildSystemPrompt(SemanticMetaResponse)} — semantic catalog
 *       injection + the JSON output contract + 3 few-shot examples (CN/EN).</li>
 *   <li>{@link #buildMessages(String, ConversationContext)} — recent multi-turn
 *       history (already trimmed by {@code ConversationService} to the 5-pair
 *       window) followed by the current user question.</li>
 *   <li>{@link #parseResponse(String)} — tolerant JSON parser that strips
 *       optional Markdown code fences, then maps to {@link IntentResult}.</li>
 * </ul>
 *
 * <p>Determinism: every parse failure or schema mismatch downgrades to
 * {@link IntentResult#empty()} so the caller can fall through to the v1
 * keyword path — provider implementations MUST surface this as
 * {@code confidence=0}, never throw (per {@link LlmProvider} contract).
 */
@Slf4j
public class ChatBiPromptBuilder {

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * Build the system prompt: task description + catalog JSON + output
     * schema + few-shot examples. Stable enough to live in the Anthropic
     * prompt cache prefix.
     */
    public String buildSystemPrompt(SemanticMetaResponse catalog) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("You are AuraBoot ChatBI, a natural-language → semantic-query translator. ");
        sb.append("Given a user question and the catalog below, emit a structured token list ");
        sb.append("that AuraBoot's TokenCompiler can compile into SQL via the semantic layer.\n\n");

        sb.append("# Catalog (available models, metrics, dimensions)\n");
        sb.append(serialiseCatalog(catalog)).append("\n\n");

        sb.append("# Output contract\n");
        sb.append("Respond with a SINGLE JSON object, no prose, matching this shape:\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"tokens\": [\n");
        sb.append("    {\"type\":\"METRIC|DIMENSION|TIME_RANGE|TOP_N|VALUE|KEYWORD\",\n");
        sb.append("     \"rawText\":\"<user's phrase>\",\n");
        sb.append("     \"resolvedCode\":\"<metric.code or dimension.code>\",\n");
        sb.append("     \"position\":0,\n");
        sb.append("     \"dateBucket\":\"day|week|month|quarter|year (DIMENSION only)\",\n");
        sb.append("     \"operator\":\"EQ|NE|GT|GTE|LT|LTE|IN|NOT_IN|LIKE|BETWEEN (filters only)\",\n");
        sb.append("     \"value\":<literal>}\n");
        sb.append("  ],\n");
        sb.append("  \"confidence\": <0.0-1.0>,\n");
        sb.append("  \"needsClarification\": <bool>,\n");
        sb.append("  \"disambiguation\": null | {\"ambiguousTerm\":\"...\",\n");
        sb.append("       \"candidates\":[{\"type\":\"METRIC\",\"code\":\"...\",\"label\":\"...\",\"score\":0.X}]},\n");
        sb.append("  \"suggestedFollowUps\": [\"<follow-up question>\", ...]\n");
        sb.append("}\n");
        sb.append("```\n\n");

        sb.append("# Rules\n");
        sb.append("- Use ONLY metric/dimension codes that exist in the catalog. Never invent codes.\n");
        sb.append("- If the user's phrase is ambiguous (matches 2+ catalog entries with close scores), ");
        sb.append("set needsClarification=true and fill disambiguation. Set confidence < 0.7.\n");
        sb.append("- Set confidence ≥ 0.9 when every token maps to a single catalog entry unambiguously.\n");
        sb.append("- For follow-up questions referencing prior turns (\"by region instead\", \"last month\"), ");
        sb.append("reuse metrics from the preceding messages and only swap the dimension/time range.\n");
        sb.append("- Up to 3 suggestedFollowUps in the user's language.\n\n");

        sb.append("# Few-shot examples\n\n");
        sb.append("## Example 1 — Chinese, single metric + time bucket\n");
        sb.append("Question: 今年华东销售额按月趋势\n");
        sb.append("Answer: {\"tokens\":[");
        sb.append("{\"type\":\"METRIC\",\"rawText\":\"销售额\",\"resolvedCode\":\"sales.total_sales\",\"position\":0},");
        sb.append("{\"type\":\"DIMENSION\",\"rawText\":\"按月\",\"resolvedCode\":\"sales.order_date\",\"dateBucket\":\"month\",\"position\":1},");
        sb.append("{\"type\":\"TIME_RANGE\",\"rawText\":\"今年\",\"resolvedCode\":\"YEAR_TO_DATE\",\"position\":2},");
        sb.append("{\"type\":\"DIMENSION\",\"rawText\":\"华东\",\"resolvedCode\":\"sales.region\",\"operator\":\"EQ\",\"value\":\"CN_EAST\",\"position\":3}");
        sb.append("],\"confidence\":0.95,\"needsClarification\":false,\"disambiguation\":null,");
        sb.append("\"suggestedFollowUps\":[\"按区域分布\",\"TOP10 客户\"]}\n\n");

        sb.append("## Example 2 — English, TOP-N + filter\n");
        sb.append("Question: top 10 customers by revenue last quarter\n");
        sb.append("Answer: {\"tokens\":[");
        sb.append("{\"type\":\"METRIC\",\"rawText\":\"revenue\",\"resolvedCode\":\"sales.total_sales\",\"position\":0},");
        sb.append("{\"type\":\"DIMENSION\",\"rawText\":\"customers\",\"resolvedCode\":\"sales.customer_id\",\"position\":1},");
        sb.append("{\"type\":\"TIME_RANGE\",\"rawText\":\"last quarter\",\"resolvedCode\":\"LAST_QUARTER\",\"position\":2},");
        sb.append("{\"type\":\"TOP_N\",\"rawText\":\"top 10\",\"value\":10,\"position\":3}");
        sb.append("],\"confidence\":0.92,\"needsClarification\":false,\"disambiguation\":null,");
        sb.append("\"suggestedFollowUps\":[\"by region\",\"compared to prior quarter\"]}\n\n");

        sb.append("## Example 3 — ambiguous term, needs clarification\n");
        sb.append("Question: 显示销量\n");
        sb.append("Answer: {\"tokens\":[],\"confidence\":0.4,\"needsClarification\":true,");
        sb.append("\"disambiguation\":{\"ambiguousTerm\":\"销量\",\"candidates\":[");
        sb.append("{\"type\":\"METRIC\",\"code\":\"sales.units_sold\",\"label\":\"销售数量\",\"score\":0.82},");
        sb.append("{\"type\":\"METRIC\",\"code\":\"sales.total_sales\",\"label\":\"销售金额\",\"score\":0.78}]},");
        sb.append("\"suggestedFollowUps\":[]}\n");

        return sb.toString();
    }

    /**
     * Build the {@code messages} array for {@link LlmChatRequest}: prior turns
     * from {@code ctx} (already trimmed to 5 pairs) followed by the current
     * user question.
     */
    public List<LlmChatRequest.Message> buildMessages(String question, ConversationContext ctx) {
        Objects.requireNonNull(question, "question");
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        if (ctx != null && ctx.getMessageHistory() != null) {
            for (ConversationContext.Message prior : ctx.getMessageHistory()) {
                LlmChatRequest.Message m = LlmChatRequest.Message.builder()
                        .role(prior.role())
                        .content(prior.content())
                        .build();
                messages.add(m);
            }
        }
        messages.add(LlmChatRequest.Message.builder()
                .role("user")
                .content(question)
                .build());
        return messages;
    }

    /**
     * Parse the LLM response text into {@link IntentResult}. Tolerant of:
     *
     * <ul>
     *   <li>Markdown code fences ({@code ```json ... ```}) — stripped.</li>
     *   <li>Leading/trailing prose — first {@code {} balanced block extracted.</li>
     *   <li>Missing optional fields ({@code disambiguation},
     *       {@code suggestedFollowUps}).</li>
     * </ul>
     *
     * <p>Any failure returns {@link IntentResult#empty()} so the caller falls
     * back to v1 keyword path. Never throws.
     */
    public IntentResult parseResponse(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            return IntentResult.empty();
        }
        String json = extractJsonBlock(responseText);
        if (json == null) {
            log.debug("LLM response had no JSON block: {}", truncate(responseText, 200));
            return IntentResult.empty();
        }
        try {
            JsonNode root = M.readTree(json);
            List<SearchToken> tokens = parseTokens(root.get("tokens"));
            double confidence = clampConfidence(root.path("confidence").asDouble(0.0));
            boolean needsClarification = root.path("needsClarification").asBoolean(false);
            Disambiguation disambiguation = parseDisambiguation(root.get("disambiguation"));
            List<String> followUps = parseStringArray(root.get("suggestedFollowUps"));
            return new IntentResult(
                    tokens,
                    confidence,
                    needsClarification,
                    disambiguation,
                    followUps,
                    LlmUsage.zero()); // usage filled in by provider
        } catch (Exception e) {
            log.warn("LLM response parse failed: {} — payload: {}",
                    e.getMessage(), truncate(json, 200));
            return IntentResult.empty();
        }
    }

    // ---------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------

    private String serialiseCatalog(SemanticMetaResponse catalog) {
        if (catalog == null || catalog.getModels() == null || catalog.getModels().isEmpty()) {
            return "(no models registered)";
        }
        try {
            ObjectNode root = M.createObjectNode();
            ArrayNode models = root.putArray("models");
            for (SemanticMetaResponse.ModelMeta m : catalog.getModels()) {
                ObjectNode modelNode = models.addObject();
                modelNode.put("code", nullSafe(m.getCode()));
                modelNode.put("label", primaryLabel(m.getLabel()));
                if (m.getDescription() != null) modelNode.put("description", m.getDescription());
                ArrayNode metrics = modelNode.putArray("metrics");
                if (m.getMetrics() != null) {
                    for (SemanticMetaResponse.MetricMeta met : m.getMetrics()) {
                        ObjectNode mn = metrics.addObject();
                        mn.put("code", nullSafe(met.getCode()));
                        mn.put("label", primaryLabel(met.getLabel()));
                        if (met.getDescription() != null) mn.put("description", met.getDescription());
                    }
                }
                ArrayNode dims = modelNode.putArray("dimensions");
                if (m.getDimensions() != null) {
                    for (SemanticMetaResponse.DimensionMeta dim : m.getDimensions()) {
                        ObjectNode dn = dims.addObject();
                        dn.put("code", nullSafe(dim.getCode()));
                        dn.put("label", primaryLabel(dim.getLabel()));
                        dn.put("primaryTime", dim.isPrimaryTime());
                        if (dim.getTimeGrains() != null && !dim.getTimeGrains().isEmpty()) {
                            ArrayNode grains = dn.putArray("timeGrains");
                            dim.getTimeGrains().forEach(grains::add);
                        }
                    }
                }
            }
            return M.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Catalog serialise failed: {}", e.getMessage());
            return "(catalog serialisation failed)";
        }
    }

    private static String primaryLabel(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) return "";
        if (labels.containsKey("zh-CN")) return labels.get("zh-CN");
        if (labels.containsKey("en-US")) return labels.get("en-US");
        return labels.values().iterator().next();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** Strip ```json fences and locate the first balanced {…} block. */
    static String extractJsonBlock(String text) {
        String s = text.trim();
        // Strip ```json ... ``` fence.
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) {
                s = s.substring(firstNl + 1);
            }
            int closing = s.lastIndexOf("```");
            if (closing >= 0) {
                s = s.substring(0, closing);
            }
            s = s.trim();
        }
        int open = s.indexOf('{');
        if (open < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escape) { escape = false; }
                else if (c == '\\') { escape = true; }
                else if (c == '"') { inString = false; }
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(open, i + 1);
                }
            }
        }
        return null;
    }

    private List<SearchToken> parseTokens(JsonNode arr) {
        List<SearchToken> tokens = new ArrayList<>();
        if (arr == null || !arr.isArray()) return tokens;
        for (JsonNode n : arr) {
            String typeStr = n.path("type").asText("");
            TokenType type = parseEnum(TokenType.class, typeStr);
            if (type == null) continue;
            String rawText = n.path("rawText").asText("");
            String resolvedCode = n.has("resolvedCode") && !n.get("resolvedCode").isNull()
                    ? n.get("resolvedCode").asText(null) : null;
            int position = n.path("position").asInt(tokens.size());
            String dateBucket = n.has("dateBucket") && !n.get("dateBucket").isNull()
                    ? n.get("dateBucket").asText(null) : null;
            com.auraboot.framework.chatbi.v2.dto.Operator op = n.has("operator") && !n.get("operator").isNull()
                    ? parseEnum(com.auraboot.framework.chatbi.v2.dto.Operator.class,
                                n.get("operator").asText())
                    : null;
            Object value = parseValue(n.get("value"));
            tokens.add(new SearchToken(type, rawText, resolvedCode, op, value,
                                       position, dateBucket, null));
        }
        return tokens;
    }

    private Object parseValue(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isInt() || n.isLong()) return n.asLong();
        if (n.isDouble() || n.isFloat()) return n.asDouble();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode el : n) list.add(parseValue(el));
            return list;
        }
        return n.asText();
    }

    private Disambiguation parseDisambiguation(JsonNode n) {
        if (n == null || n.isNull() || !n.isObject()) return null;
        String term = n.path("ambiguousTerm").asText("");
        List<Disambiguation.Candidate> candidates = new ArrayList<>();
        JsonNode cs = n.get("candidates");
        if (cs != null && cs.isArray()) {
            for (JsonNode c : cs) {
                candidates.add(new Disambiguation.Candidate(
                        c.path("type").asText("METRIC"),
                        c.path("code").asText(""),
                        c.path("label").asText(""),
                        c.path("score").asDouble(0.0)));
            }
        }
        return new Disambiguation(term, candidates);
    }

    private List<String> parseStringArray(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || !n.isArray()) return out;
        for (JsonNode el : n) {
            if (el.isTextual()) out.add(el.asText());
        }
        return out;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static double clampConfidence(double v) {
        if (Double.isNaN(v) || v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
