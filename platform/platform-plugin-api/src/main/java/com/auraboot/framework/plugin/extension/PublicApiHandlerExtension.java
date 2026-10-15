package com.auraboot.framework.plugin.extension;

import org.pf4j.ExtensionPoint;
import java.util.Map;
import java.util.Set;

/** Explicit, typed, read-only public business API extension. No internal route reflection is used. */
public interface PublicApiHandlerExtension extends ExtensionPoint {
    enum ValueType { STRING, INTEGER, DECIMAL, BOOLEAN }
    /** Version identifies public handler semantics; compatible fixes preserve it, while release receipts pin implementation bytes. */
    record Definition(String code, String name, String version, Map<String, ValueType> parameters,
                      Set<String> required, Map<String, ValueType> output) {
        public Definition {
            parameters = Map.copyOf(parameters); required = Set.copyOf(required); output = Map.copyOf(output);
            if (code == null || !code.matches("[a-z][a-z0-9.:-]{1,127}") || name == null || name.isBlank()
                    || version == null || version.isBlank() || parameters.size() > 32 || output.isEmpty() || output.size() > 32
                    || !parameters.keySet().containsAll(required)) throw new IllegalArgumentException("Invalid public handler definition");
            java.util.stream.Stream.concat(parameters.keySet().stream(), output.keySet().stream()).forEach(key -> {
                if (!key.matches("[A-Za-z][A-Za-z0-9]{0,99}")) throw new IllegalArgumentException("Invalid public handler property");
            });
        }
    }
    /** Reads the bound public resource, preserving current row, field, page and SQL budgets. */
    @FunctionalInterface interface ResourceReader { Map<String, Object> page(int limit); }
    default Map<String, String> localizedDisplayNames() { return Map.of(); }
    Definition definition();
    Map<String, Object> execute(Map<String, Object> parameters, ResourceReader resource);
}
