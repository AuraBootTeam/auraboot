package com.auraboot.framework.decision.table;

import com.auraboot.framework.decision.ast.FunctionRegistry;
import com.auraboot.framework.decision.ast.DataType;
import com.auraboot.framework.decision.ast.Operator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal DMN unary-test parser for decision-table cells. It intentionally supports the finite,
 * auditable subset used by the workbench while the backend remains the authority for runtime.
 */
public final class DecisionTableFeel {

    public record ParsedTest(Operator operator, Object value) {}

    private static final Pattern COMPARISON = Pattern.compile("^(>=|<=|>|<|!=|=)\\s*(.+)$");
    private static final Pattern RANGE = Pattern.compile("^\\[\\s*(.+?)\\s*\\.\\.\\s*(.+?)\\s*]$");
    private static final Pattern FUNCTION = Pattern.compile("^([A-Za-z][A-Za-z .]*[A-Za-z])\\s*\\((.*)\\)$");
    private static final Set<String> LITERAL_FUNCTIONS = Set.of("date", "time", "date and time", "duration");

    private DecisionTableFeel() {}

    public static boolean hasText(DecisionTable.Cell cell) {
        return cell != null && cell.feel() != null && !cell.feel().isBlank();
    }

    public static boolean isSupportedSyntax(String feel) {
        try {
            parse(feel, null);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String text = feel == null ? "" : feel.trim();
        if (text.isEmpty() || "-".equals(text)) {
            return true;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if ("null".equals(lower) || "not(null)".equals(lower) || "not null".equals(lower)) {
            return true;
        }
        Matcher range = RANGE.matcher(text);
        if (range.matches()) {
            return !looksUnsupportedLiteral(range.group(1)) && !looksUnsupportedLiteral(range.group(2));
        }
        Matcher comparison = COMPARISON.matcher(text);
        if (comparison.matches()) {
            return !looksUnsupportedLiteral(comparison.group(2));
        }
        if (isSupportedFunctionLiteral(text)) {
            return true;
        }
        if (text.contains(",")) {
            return splitTopLevel(text).stream().noneMatch(DecisionTableFeel::looksUnsupportedLiteral);
        }
        return !looksUnsupportedLiteral(text);
    }

    public static List<ParsedTest> parse(String feel, DataType dataType) {
        String text = feel == null ? "" : feel.trim();
        if (text.isEmpty() || "-".equals(text)) {
            return List.of();
        }

        String lower = text.toLowerCase(Locale.ROOT);
        if ("null".equals(lower)) {
            return List.of(new ParsedTest(Operator.IS_NULL, null));
        }
        if ("not(null)".equals(lower) || "not null".equals(lower)) {
            return List.of(new ParsedTest(Operator.IS_NOT_NULL, null));
        }

        Matcher range = RANGE.matcher(text);
        if (range.matches()) {
            return List.of(new ParsedTest(Operator.BETWEEN,
                    List.of(parseLiteral(range.group(1), dataType), parseLiteral(range.group(2), dataType))));
        }

        Matcher comparison = COMPARISON.matcher(text);
        if (comparison.matches()) {
            Operator op = switch (comparison.group(1)) {
                case ">" -> Operator.GT;
                case ">=" -> Operator.GTE;
                case "<" -> Operator.LT;
                case "<=" -> Operator.LTE;
                case "!=" -> Operator.NE;
                case "=" -> Operator.EQ;
                default -> throw new IllegalArgumentException("Unsupported FEEL operator: " + comparison.group(1));
            };
            return List.of(new ParsedTest(op, parseLiteral(comparison.group(2), dataType)));
        }

        if (isSupportedFunctionLiteral(text)) {
            return List.of(new ParsedTest(Operator.EQ, parseLiteral(text, dataType)));
        }

        if (text.contains(",")) {
            List<Object> values = splitTopLevel(text).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(v -> parseLiteral(v, dataType))
                    .toList();
            if (values.isEmpty()) {
                throw new IllegalArgumentException("FEEL list cell has no values: " + feel);
            }
            return List.of(new ParsedTest(Operator.IN, values));
        }

        return List.of(new ParsedTest(Operator.EQ, parseLiteral(text, dataType)));
    }

    private static Object parseLiteral(String raw, DataType dataType) {
        String value = raw == null ? "" : raw.trim();
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        if ("null".equalsIgnoreCase(value)) {
            return null;
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        Object functionValue = parseFunctionLiteral(value);
        if (functionValue != null) {
            return functionValue;
        }
        if (looksFunctionLike(value)) {
            throw new IllegalArgumentException("Unsupported FEEL function literal: " + raw);
        }
        if (dataType != null && dataType.isNumeric()) {
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("FEEL numeric literal is invalid: " + raw);
            }
        }
        return value;
    }

    private static Object parseFunctionLiteral(String raw) {
        Matcher matcher = FUNCTION.matcher(raw);
        if (!matcher.matches()) {
            return null;
        }
        String name = normalizeFunctionName(matcher.group(1));
        if (!LITERAL_FUNCTIONS.contains(name)) {
            return null;
        }
        List<Object> args = splitTopLevel(matcher.group(2)).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(DecisionTableFeel::parseFunctionArg)
                .toList();
        return FunctionRegistry.withDefaults().invoke(name, args);
    }

    private static Object parseFunctionArg(String raw) {
        String value = raw == null ? "" : raw.trim();
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        if ("null".equalsIgnoreCase(value)) {
            return null;
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        Object nested = parseFunctionLiteral(value);
        if (nested != null) {
            return nested;
        }
        if (looksFunctionLike(value)) {
            throw new IllegalArgumentException("Unsupported FEEL function argument: " + raw);
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static boolean isSupportedFunctionLiteral(String raw) {
        Matcher matcher = FUNCTION.matcher(raw);
        return matcher.matches() && LITERAL_FUNCTIONS.contains(normalizeFunctionName(matcher.group(1)));
    }

    private static String normalizeFunctionName(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean looksFunctionLike(String value) {
        return value.contains("(") || value.contains(")");
    }

    private static boolean looksUnsupportedLiteral(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (isSupportedFunctionLiteral(value)) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return looksFunctionLike(value)
                || lower.matches(".*\\b(if|then|else|and|or|between|date|time|duration|not)\\b.*");
    }

    private static List<String> splitTopLevel(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                current.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                current.append(ch);
                continue;
            }
            if (ch == '(') {
                depth += 1;
                current.append(ch);
                continue;
            }
            if (ch == ')') {
                depth -= 1;
                if (depth < 0) {
                    throw new IllegalArgumentException("FEEL literal has unmatched ')': " + text);
                }
                current.append(ch);
                continue;
            }
            if (ch == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (quote != 0) {
            throw new IllegalArgumentException("FEEL literal has unterminated quote: " + text);
        }
        if (depth != 0) {
            throw new IllegalArgumentException("FEEL literal has unbalanced parentheses: " + text);
        }
        parts.add(current.toString());
        return parts;
    }
}
