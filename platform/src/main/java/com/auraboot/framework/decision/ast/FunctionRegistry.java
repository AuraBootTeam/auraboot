package com.auraboot.framework.decision.ast;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Whitelist of pure functions usable inside a Condition AST (docs/1.md §14.9). Functions
 * must be pure, side-effect free, and registered; arbitrary bean/method invocation,
 * reflection, IO, db, system clock and randomness are forbidden. Time is taken from
 * {@code context.time.now}, never read inside a function.
 *
 * <p>Resolving an unregistered function name throws, which surfaces as an evaluation error
 * rather than silently returning a value.
 */
public final class FunctionRegistry {

    /** A registered pure function: name -> (args -> result). */
    public record DecisionFunction(String name, DataType returnType, Function<List<Object>, Object> impl) {}

    private final Map<String, DecisionFunction> functions = new ConcurrentHashMap<>();

    public FunctionRegistry register(DecisionFunction fn) {
        functions.put(normalizeName(fn.name()), fn);
        return this;
    }

    public boolean isRegistered(String name) {
        return functions.containsKey(normalizeName(name));
    }

    public Object invoke(String name, List<Object> args) {
        DecisionFunction fn = functions.get(normalizeName(name));
        if (fn == null) {
            throw new IllegalArgumentException("Function not whitelisted: " + name);
        }
        return fn.impl().apply(args);
    }

    public DataType returnType(String name) {
        DecisionFunction fn = functions.get(normalizeName(name));
        return fn == null ? null : fn.returnType();
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * A registry seeded with a few safe, pure built-ins. More are registered by callers;
     * the heavyweight business-calendar functions live behind their own resolvers.
     */
    public static FunctionRegistry withDefaults() {
        FunctionRegistry r = new FunctionRegistry();
        r.register(new DecisionFunction("string.length", DataType.INTEGER,
                args -> args.isEmpty() || args.get(0) == null ? 0 : String.valueOf(args.get(0)).length()));
        r.register(new DecisionFunction("string.lower", DataType.STRING,
                args -> args.isEmpty() || args.get(0) == null ? null : String.valueOf(args.get(0)).toLowerCase()));
        r.register(new DecisionFunction("string.upper", DataType.STRING,
                args -> args.isEmpty() || args.get(0) == null ? null : String.valueOf(args.get(0)).toUpperCase()));
        r.register(new DecisionFunction("collection.size", DataType.INTEGER,
                args -> args.isEmpty() || !(args.get(0) instanceof List<?> l) ? 0 : l.size()));
        r.register(new DecisionFunction("date", DataType.DATE, FunctionRegistry::date));
        r.register(new DecisionFunction("time", DataType.TIME, FunctionRegistry::time));
        r.register(new DecisionFunction("date and time", DataType.DATETIME, FunctionRegistry::dateAndTime));
        r.register(new DecisionFunction("duration", DataType.DURATION, FunctionRegistry::duration));
        return r;
    }

    private static LocalDate date(List<Object> args) {
        if (args.size() == 1) {
            LocalDate parsed = toLocalDate(args.get(0));
            if (parsed != null) {
                return parsed;
            }
            throw new IllegalArgumentException("date(value) expects ISO date text or date-like value");
        }
        if (args.size() == 3) {
            return LocalDate.of(intArg(args, 0, "year"), intArg(args, 1, "month"), intArg(args, 2, "day"));
        }
        throw new IllegalArgumentException("date(...) expects 1 or 3 arguments");
    }

    private static LocalTime time(List<Object> args) {
        if (args.size() == 1) {
            LocalTime parsed = toLocalTime(args.get(0));
            if (parsed != null) {
                return parsed;
            }
            throw new IllegalArgumentException("time(value) expects ISO time text");
        }
        if (args.size() == 3) {
            return LocalTime.of(intArg(args, 0, "hour"), intArg(args, 1, "minute"), intArg(args, 2, "second"));
        }
        throw new IllegalArgumentException("time(...) expects 1 or 3 arguments");
    }

    private static Object dateAndTime(List<Object> args) {
        if (args.size() == 1) {
            Object value = args.get(0);
            Instant instant = toInstant(value);
            if (instant != null) {
                return instant;
            }
            if (value instanceof String s && !s.isBlank()) {
                try {
                    return LocalDateTime.parse(s.trim());
                } catch (DateTimeParseException ignored) {
                    // handled below
                }
            }
            throw new IllegalArgumentException("date and time(value) expects ISO datetime text");
        }
        if (args.size() == 2) {
            LocalDate d = toLocalDate(args.get(0));
            LocalTime t = toLocalTime(args.get(1));
            if (d != null && t != null) {
                return LocalDateTime.of(d, t);
            }
            throw new IllegalArgumentException("date and time(date, time) expects date and time values");
        }
        if (args.size() == 6) {
            return LocalDateTime.of(
                    intArg(args, 0, "year"),
                    intArg(args, 1, "month"),
                    intArg(args, 2, "day"),
                    intArg(args, 3, "hour"),
                    intArg(args, 4, "minute"),
                    intArg(args, 5, "second"));
        }
        throw new IllegalArgumentException("date and time(...) expects 1, 2, or 6 arguments");
    }

    private static Duration duration(List<Object> args) {
        if (args.size() != 1) {
            throw new IllegalArgumentException("duration(...) expects exactly 1 argument");
        }
        Object value = args.get(0);
        if (value instanceof Duration d) {
            return d;
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Duration.parse(s.trim());
            } catch (DateTimeParseException ignored) {
                // handled below
            }
        }
        throw new IllegalArgumentException("duration(value) expects ISO-8601 day-time duration text");
    }

    private static int intArg(List<Object> args, int index, String label) {
        Object value = args.get(index);
        if (value instanceof Number n) {
            return new BigDecimal(n.toString()).intValueExact();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim()).intValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
                // handled below
            }
        }
        throw new IllegalArgumentException(label + " must be an integer");
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof LocalDateTime dt) {
            return dt.toLocalDate();
        }
        if (value instanceof Instant i) {
            return LocalDateTime.ofInstant(i, ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof Date d) {
            return LocalDateTime.ofInstant(d.toInstant(), ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof String s && !s.isBlank()) {
            String text = s.trim();
            try {
                return LocalDate.parse(text);
            } catch (DateTimeParseException ignored) {
                Instant instant = toInstant(text);
                return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC).toLocalDate();
            }
        }
        return null;
    }

    private static LocalTime toLocalTime(Object value) {
        if (value instanceof LocalTime t) {
            return t;
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return LocalTime.parse(s.trim());
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof Date d) {
            return d.toInstant();
        }
        if (value instanceof LocalDateTime dt) {
            return dt.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof LocalDate d) {
            return d.atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        if (value instanceof String s && !s.isBlank()) {
            String text = s.trim();
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ignored) {
                // Try common JSON datetime variants below.
            }
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException ignored) {
                // Try ZonedDateTime.
            }
            try {
                return ZonedDateTime.parse(text).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }
}
