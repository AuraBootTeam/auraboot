package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.plugin.extension.RestRoute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches a concrete {@code (method, path)} against a {@link RestRoute} pathPattern and
 * extracts path variables. Pure function — no Spring, no I/O. Used by the plugin REST
 * dispatcher to resolve which extension handles an inbound request.
 *
 * <p>pathPattern segments like {@code {batchId}} become single-segment capture groups
 * ({@code [^/]+}); literal segments are matched verbatim. Path variable names are mapped
 * positionally (so names need not be valid Java regex group identifiers).
 */
public final class RestRouteMatcher {

    private RestRouteMatcher() {
    }

    private static final Pattern VAR = Pattern.compile("\\{([^/}]+)}");

    /**
     * @return the resolved path variables if {@code method}+{@code path} match {@code route},
     *         otherwise {@link Optional#empty()}.
     */
    public static Optional<Map<String, String>> match(RestRoute route, String method, String path) {
        if (route == null || method == null || path == null) {
            return Optional.empty();
        }
        if (!route.method().equalsIgnoreCase(method)) {
            return Optional.empty();
        }

        String pattern = route.pathPattern();
        List<String> varNames = new ArrayList<>();
        StringBuilder regex = new StringBuilder("^");
        Matcher varMatcher = VAR.matcher(pattern);
        int last = 0;
        while (varMatcher.find()) {
            regex.append(Pattern.quote(pattern.substring(last, varMatcher.start())));
            varNames.add(varMatcher.group(1));
            regex.append("([^/]+)");
            last = varMatcher.end();
        }
        regex.append(Pattern.quote(pattern.substring(last)));
        regex.append("$");

        Matcher pathMatcher = Pattern.compile(regex.toString()).matcher(path);
        if (!pathMatcher.matches()) {
            return Optional.empty();
        }

        Map<String, String> vars = new LinkedHashMap<>();
        for (int i = 0; i < varNames.size(); i++) {
            vars.put(varNames.get(i), pathMatcher.group(i + 1));
        }
        return Optional.of(vars);
    }
}
