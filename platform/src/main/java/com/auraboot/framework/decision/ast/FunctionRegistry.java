package com.auraboot.framework.decision.ast;

import java.util.List;
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
        functions.put(fn.name(), fn);
        return this;
    }

    public boolean isRegistered(String name) {
        return functions.containsKey(name);
    }

    public Object invoke(String name, List<Object> args) {
        DecisionFunction fn = functions.get(name);
        if (fn == null) {
            throw new IllegalArgumentException("Function not whitelisted: " + name);
        }
        return fn.impl().apply(args);
    }

    public DataType returnType(String name) {
        DecisionFunction fn = functions.get(name);
        return fn == null ? null : fn.returnType();
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
        return r;
    }
}
