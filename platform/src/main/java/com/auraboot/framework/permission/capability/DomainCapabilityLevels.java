package com.auraboot.framework.permission.capability;

import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Optional three-level authoring convention; atomic runtime grants and scopes are unchanged. */
final class DomainCapabilityLevels {
    private DomainCapabilityLevels() {}

    static List<CapabilityDefinitionDTO> currentDeclarations(List<CapabilityDefinitionDTO> declarations) {
        Map<String, CapabilityDefinitionDTO> byCode = declarations.stream()
                .filter(CapabilityDefinitionDTO::isValid)
                .collect(Collectors.toMap(CapabilityDefinitionDTO::getCode, Function.identity(), (a, b) -> a));
        Map<String, Set<String>> domains = new java.util.LinkedHashMap<>();
        for (CapabilityDefinitionDTO read : declarations) {
            if (read.getCode() == null || !read.getCode().endsWith(".cap.read")) continue;
            String prefix = read.getCode().substring(0, read.getCode().length() - 4);
            CapabilityDefinitionDTO write = byCode.get(prefix + "write");
            CapabilityDefinitionDTO manage = byCode.get(prefix + "manage");
            if (!read.isValid() || write == null || manage == null
                    || !"viewer".equals(read.getTier()) || !"editor".equals(write.getTier())
                    || !"admin".equals(manage.getTier())) continue;
            Set<String> reads = new HashSet<>(read.getIncludes());
            Set<String> writes = new HashSet<>(write.getIncludes());
            Set<String> manages = new HashSet<>(manage.getIncludes());
            if (!writes.containsAll(reads) || !manages.containsAll(writes)
                    || writes.equals(reads) || manages.equals(writes)) continue;
            domains.put(prefix, manages);
        }
        // Old imported declarations may remain in storage. Hide only explicitly covered aliases
        // of a complete, nested domain triple; never delete grants or suppress unrelated domains.
        return declarations.stream().filter(declaration -> domains.entrySet().stream().noneMatch(domain ->
                declaration.isValid() && declaration.getCode().startsWith(domain.getKey())
                        && !Set.of(domain.getKey() + "read", domain.getKey() + "write", domain.getKey() + "manage")
                            .contains(declaration.getCode())
                        && domain.getValue().containsAll(declaration.getIncludes()))).toList();
    }
}
