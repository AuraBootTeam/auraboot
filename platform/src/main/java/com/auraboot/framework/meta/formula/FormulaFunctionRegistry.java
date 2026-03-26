package com.auraboot.framework.meta.formula;

import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry for formula functions available in SpEL expressions
 */
@Slf4j
@Component
public class FormulaFunctionRegistry {

    private final Map<String, Method> functions = new HashMap<>();
    private final Map<String, FunctionInfo> functionInfos = new HashMap<>();

    @PostConstruct
    public void init() {
        registerBuiltInFunctions();
        log.info("Registered {} formula functions", functions.size());
    }

    private void registerBuiltInFunctions() {
        try {
            Class<?> clazz = FormulaFunctions.class;
            for (Method method : clazz.getDeclaredMethods()) {
                FormulaFunction annotation = method.getAnnotation(FormulaFunction.class);
                if (annotation != null) {
                    String name = annotation.value();
                    functions.put(name, method);
                    functionInfos.put(name, new FunctionInfo(
                        name,
                        annotation.description(),
                        annotation.category(),
                        annotation.example(),
                        method.getParameterTypes()
                    ));
                }
            }
        } catch (Exception e) {
            log.error("Failed to register formula functions", e);
        }
    }

    public void registerToContext(SimpleEvaluationContext context) {
        for (Map.Entry<String, Method> entry : functions.entrySet()) {
            context.setVariable(entry.getKey(), entry.getValue());
        }
    }

    public List<FunctionInfo> getAllFunctions() {
        return new ArrayList<>(functionInfos.values());
    }

    public List<FunctionInfo> getFunctionsByCategory(String category) {
        return functionInfos.values().stream()
            .filter(f -> category.equals(f.category()))
            .collect(Collectors.toList());
    }

    public record FunctionInfo(
        String name,
        String description,
        String category,
        String example,
        Class<?>[] parameterTypes
    ) {}

    /**
     * Built-in formula functions
     */
    public static class FormulaFunctions {

        // ========== Text Functions ==========

        @FormulaFunction(value = "concat", description = "Concatenate strings", category = "text", example = "#CONCAT('Hello', ' ', 'World')")
        public static String concat(String... parts) {
            return String.join("", parts);
        }

        @FormulaFunction(value = "upper", description = "Convert to uppercase", category = "text", example = "#UPPER('hello')")
        public static String upper(String text) {
            return text == null ? null : text.toUpperCase();
        }

        @FormulaFunction(value = "lower", description = "Convert to lowercase", category = "text", example = "#LOWER('hello')")
        public static String lower(String text) {
            return text == null ? null : text.toLowerCase();
        }

        @FormulaFunction(value = "trim", description = "Remove leading/trailing whitespace", category = "text", example = "#TRIM('  hello  ')")
        public static String trim(String text) {
            return text == null ? null : text.trim();
        }

        @FormulaFunction(value = "left", description = "Get left N characters", category = "text", example = "#LEFT('hello', 3)")
        public static String left(String text, int length) {
            if (text == null) return null;
            return text.length() <= length ? text : text.substring(0, length);
        }

        @FormulaFunction(value = "right", description = "Get right N characters", category = "text", example = "#RIGHT('hello', 3)")
        public static String right(String text, int length) {
            if (text == null) return null;
            return text.length() <= length ? text : text.substring(text.length() - length);
        }

        @FormulaFunction(value = "len", description = "Get string length", category = "text", example = "#LEN('hello')")
        public static int len(String text) {
            return text == null ? 0 : text.length();
        }

        @FormulaFunction(value = "replace", description = "Replace text", category = "text", example = "#REPLACE('hello', 'l', 'x')")
        public static String replace(String text, String search, String replacement) {
            if (text == null) return null;
            return text.replace(search, replacement);
        }

        // ========== Math Functions ==========

        @FormulaFunction(value = "round", description = "Round to decimal places", category = "math", example = "#ROUND(3.14159, 2)")
        public static double round(double value, int decimals) {
            double factor = Math.pow(10, decimals);
            return Math.round(value * factor) / factor;
        }

        @FormulaFunction(value = "floor", description = "Round down", category = "math", example = "#FLOOR(3.7)")
        public static double floor(double value) {
            return Math.floor(value);
        }

        @FormulaFunction(value = "ceil", description = "Round up", category = "math", example = "#CEIL(3.2)")
        public static double ceil(double value) {
            return Math.ceil(value);
        }

        @FormulaFunction(value = "abs", description = "Absolute value", category = "math", example = "#ABS(-5)")
        public static double abs(double value) {
            return Math.abs(value);
        }

        @FormulaFunction(value = "min", description = "Minimum value", category = "math", example = "#MIN(1, 2, 3)")
        public static double min(double... values) {
            return Arrays.stream(values).min().orElse(0);
        }

        @FormulaFunction(value = "max", description = "Maximum value", category = "math", example = "#MAX(1, 2, 3)")
        public static double max(double... values) {
            return Arrays.stream(values).max().orElse(0);
        }

        @FormulaFunction(value = "sum", description = "Sum of values", category = "math", example = "#SUM(1, 2, 3)")
        public static double sum(double... values) {
            return Arrays.stream(values).sum();
        }

        @FormulaFunction(value = "avg", description = "Average of values", category = "math", example = "#AVG(1, 2, 3)")
        public static double avg(double... values) {
            return Arrays.stream(values).average().orElse(0);
        }

        @FormulaFunction(value = "pow", description = "Power", category = "math", example = "#POW(2, 3)")
        public static double pow(double base, double exponent) {
            return Math.pow(base, exponent);
        }

        @FormulaFunction(value = "sqrt", description = "Square root", category = "math", example = "#SQRT(16)")
        public static double sqrt(double value) {
            return Math.sqrt(value);
        }

        // ========== Date Functions ==========

        @FormulaFunction(value = "now", description = "Current UTC instant", category = "date", example = "#NOW()")
        public static Instant now() {
            return Instant.now();
        }

        @FormulaFunction(value = "today", description = "Current UTC date", category = "date", example = "#TODAY()")
        public static LocalDate today() {
            return Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
        }

        @FormulaFunction(value = "year", description = "Get year", category = "date", example = "#YEAR(#TODAY())")
        public static int year(LocalDate date) {
            return date == null ? 0 : date.getYear();
        }

        @FormulaFunction(value = "month", description = "Get month", category = "date", example = "#MONTH(#TODAY())")
        public static int month(LocalDate date) {
            return date == null ? 0 : date.getMonthValue();
        }

        @FormulaFunction(value = "day", description = "Get day of month", category = "date", example = "#DAY(#TODAY())")
        public static int day(LocalDate date) {
            return date == null ? 0 : date.getDayOfMonth();
        }

        @FormulaFunction(value = "date_add", description = "Add days to date", category = "date", example = "#DATE_ADD(#TODAY(), 7)")
        public static LocalDate dateAdd(LocalDate date, int days) {
            return date == null ? null : date.plusDays(days);
        }

        @FormulaFunction(value = "date_diff", description = "Days between dates", category = "date", example = "#DATE_DIFF(date1, date2)")
        public static long dateDiff(LocalDate date1, LocalDate date2) {
            if (date1 == null || date2 == null) return 0;
            return ChronoUnit.DAYS.between(date1, date2);
        }

        @FormulaFunction(value = "date_format", description = "Format date", category = "date", example = "#DATE_FORMAT(#TODAY(), 'yyyy-MM-dd')")
        public static String dateFormat(LocalDate date, String pattern) {
            if (date == null) return null;
            return date.format(DateTimeFormatter.ofPattern(pattern));
        }

        // ========== Logical Functions ==========

        @FormulaFunction(value = "if", description = "Conditional value", category = "logical", example = "#if(true, 'yes', 'no')")
        public static Object ifFunc(boolean condition, Object trueValue, Object falseValue) {
            return condition ? trueValue : falseValue;
        }

        @FormulaFunction(value = "isnull", description = "Check if null", category = "logical", example = "#ISNULL(value)")
        public static boolean isNull(Object value) {
            return value == null;
        }

        @FormulaFunction(value = "ifnull", description = "Default if null", category = "logical", example = "#IFNULL(value, 'default')")
        public static Object ifNull(Object value, Object defaultValue) {
            return value == null ? defaultValue : value;
        }

        @FormulaFunction(value = "and", description = "Logical AND", category = "logical", example = "#AND(true, true)")
        public static boolean and(boolean... values) {
            for (boolean v : values) {
                if (!v) return false;
            }
            return true;
        }

        @FormulaFunction(value = "or", description = "Logical OR", category = "logical", example = "#OR(true, false)")
        public static boolean or(boolean... values) {
            for (boolean v : values) {
                if (v) return true;
            }
            return false;
        }

        @FormulaFunction(value = "not", description = "Logical NOT", category = "logical", example = "#NOT(false)")
        public static boolean not(boolean value) {
            return !value;
        }

        @FormulaFunction(value = "switch", description = "Match value and return corresponding result", category = "logical", example = "#switch(status, 'draft', 'Draft', 'active', 'Active', 'Unknown')")
        public static Object switchFunc(Object value, Object... pairs) {
            if (value == null || pairs == null) return null;
            String strVal = String.valueOf(value);
            // pairs: match1, result1, match2, result2, ..., [defaultResult]
            for (int i = 0; i < pairs.length - 1; i += 2) {
                if (String.valueOf(pairs[i]).equals(strVal)) {
                    return pairs[i + 1];
                }
            }
            // If odd number of args, last is default
            return pairs.length % 2 == 1 ? pairs[pairs.length - 1] : null;
        }

        // ========== Additional Text Functions (GAP-125) ==========

        @FormulaFunction(value = "concatenate", description = "Concatenate multiple values (alias for CONCAT)", category = "text", example = "#CONCATENATE('Hello', ' ', 'World')")
        public static String concatenate(Object... parts) {
            if (parts == null) return "";
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) {
                sb.append(p == null ? "" : String.valueOf(p));
            }
            return sb.toString();
        }

        @FormulaFunction(value = "contains", description = "Check if text contains substring", category = "text", example = "#CONTAINS('hello world', 'world')")
        public static boolean contains(String text, String search) {
            if (text == null || search == null) return false;
            return text.contains(search);
        }

        @FormulaFunction(value = "substitute", description = "Replace first occurrence", category = "text", example = "#SUBSTITUTE('hello', 'l', 'x')")
        public static String substitute(String text, String search, String replacement) {
            if (text == null || search == null) return text;
            return text.replaceFirst(java.util.regex.Pattern.quote(search), replacement == null ? "" : replacement);
        }

        @FormulaFunction(value = "mid", description = "Extract substring", category = "text", example = "#MID('hello', 1, 3)")
        public static String mid(String text, int start, int length) {
            if (text == null) return null;
            int s = Math.max(0, start);
            int e = Math.min(text.length(), s + length);
            return text.substring(s, e);
        }

        // ========== Additional Date Functions (GAP-125) ==========

        @FormulaFunction(value = "dateadd", description = "Add time units to date (unit: day/week/month/year)", category = "date", example = "#DATEADD(#TODAY(), 7, 'day')")
        public static LocalDate dateadd(LocalDate date, int amount, String unit) {
            if (date == null || unit == null) return null;
            return switch (unit.toLowerCase()) {
                case "day", "days" -> date.plusDays(amount);
                case "week", "weeks" -> date.plusWeeks(amount);
                case "month", "months" -> date.plusMonths(amount);
                case "year", "years" -> date.plusYears(amount);
                default -> date.plusDays(amount);
            };
        }

        @FormulaFunction(value = "weekday", description = "Get day of week (1=Mon, 7=Sun)", category = "date", example = "#WEEKDAY(#TODAY())")
        public static int weekday(LocalDate date) {
            return date == null ? 0 : date.getDayOfWeek().getValue();
        }

        @FormulaFunction(value = "eomonth", description = "Last day of month offset", category = "date", example = "#EOMONTH(#TODAY(), 0)")
        public static LocalDate eomonth(LocalDate date, int monthOffset) {
            if (date == null) return null;
            return date.plusMonths(monthOffset).withDayOfMonth(date.plusMonths(monthOffset).lengthOfMonth());
        }

        // ========== Additional Math Functions (GAP-125) ==========

        @FormulaFunction(value = "mod", description = "Modulo (remainder)", category = "math", example = "#MOD(10, 3)")
        public static double mod(double value, double divisor) {
            return value % divisor;
        }

        @FormulaFunction(value = "int", description = "Truncate to integer", category = "math", example = "#INT(3.7)")
        public static long intFunc(double value) {
            return (long) value;
        }
    }
}
