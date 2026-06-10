package com.auraboot.framework.decision.table;

import com.auraboot.framework.decision.ast.DataType;
import com.auraboot.framework.decision.ast.Operator;

import java.math.BigDecimal;
import java.util.Arrays;
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

    private DecisionTableFeel() {}

    public static boolean hasText(DecisionTable.Cell cell) {
        return cell != null && cell.feel() != null && !cell.feel().isBlank();
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

        if (text.contains(",")) {
            List<Object> values = Arrays.stream(text.split(","))
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
        if (dataType != null && dataType.isNumeric()) {
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("FEEL numeric literal is invalid: " + raw);
            }
        }
        return value;
    }
}
