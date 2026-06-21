package com.auraboot.framework.permission.capability;

import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the permission v2 capability view: declared capabilities (from plugins'
 * capabilities.json) plus convention-derived capabilities for any permission code not covered by a
 * declaration, each marked granted when the subject holds all of its included codes. Capability is
 * an authoring/display abstraction — it always resolves down to existing permission codes, so the
 * runtime engine is untouched.
 */
@Component
public class CapabilityResolver {

    public List<CapabilityGroup> resolve(List<CapabilityDefinitionDTO> declarations,
                                         List<String> allPermissionCodes,
                                         Set<String> grantedCodes) {
        return resolve(declarations, allPermissionCodes, grantedCodes, Map.of());
    }

    /**
     * Resolve the capability view, deriving business-language labels for convention-derived
     * capabilities from {@code permissionNames} (permission code -&gt; localized display name).
     * The bundle label for a {@code module.resource} resource is the common stem of its action
     * names (e.g. {@code 查看许可证 / 新增许可证} -&gt; {@code 许可证}); failing that the most
     * representative action name; failing that the raw resource segment (legacy behaviour). The
     * module group stays the raw module code — the frontend localizes it via {@code
     * permission.module.<code>} i18n so no server-side i18n lookup is needed here.
     */
    public List<CapabilityGroup> resolve(List<CapabilityDefinitionDTO> declarations,
                                         List<String> allPermissionCodes,
                                         Set<String> grantedCodes,
                                         Map<String, String> permissionNames) {
        List<CapabilityDefinitionDTO> decls = declarations == null ? List.of() : declarations;
        List<String> allCodes = allPermissionCodes == null ? List.of() : allPermissionCodes;
        Set<String> granted = grantedCodes == null ? Set.of() : grantedCodes;
        Map<String, String> names = permissionNames == null ? Map.of() : permissionNames;

        // group name -> capabilities (preserve first-seen order: declared groups before derived)
        LinkedHashMap<String, List<Capability>> byGroup = new LinkedHashMap<>();
        Set<String> coveredCodes = new HashSet<>();

        // 1. Declared capabilities, lowest order first.
        decls.stream()
                .filter(CapabilityDefinitionDTO::isValid)
                .sorted(Comparator.comparingInt(d -> d.getOrder() == null ? 100 : d.getOrder()))
                .forEach(d -> {
                    coveredCodes.addAll(d.getIncludes());
                    Capability cap = Capability.builder()
                            .code(d.getCode())
                            .group(d.getGroup())
                            .label(label(d))
                            .sensitive(Boolean.TRUE.equals(d.getSensitive()))
                            .tier(d.getTier())
                            .includes(d.getIncludes())
                            .granted(granted.containsAll(d.getIncludes()))
                            .conventionDerived(false)
                            .build();
                    byGroup.computeIfAbsent(d.getGroup(), g -> new ArrayList<>()).add(cap);
                });

        // 2. Convention-derive any uncovered code, grouped by module -> resource.
        LinkedHashMap<String, List<String>> byModuleResource = new LinkedHashMap<>();
        for (String code : allCodes) {
            if (coveredCodes.contains(code)) {
                continue;
            }
            int firstDot = code.indexOf('.');
            int secondDot = firstDot < 0 ? -1 : code.indexOf('.', firstDot + 1);
            if (firstDot < 0 || secondDot < 0) {
                continue; // not a module.resource.action code — nothing to derive
            }
            String moduleResource = code.substring(0, secondDot); // module.resource
            byModuleResource.computeIfAbsent(moduleResource, k -> new ArrayList<>()).add(code);
        }
        byModuleResource.forEach((moduleResource, includes) -> {
            String module = moduleResource.substring(0, moduleResource.indexOf('.'));
            String resource = moduleResource.substring(moduleResource.indexOf('.') + 1);
            Capability cap = Capability.builder()
                    .code(moduleResource)
                    .group(module)
                    .label(conventionLabel(includes, names, resource))
                    .sensitive(false)
                    .includes(includes)
                    .granted(granted.containsAll(includes))
                    .conventionDerived(true)
                    .build();
            byGroup.computeIfAbsent(module, g -> new ArrayList<>()).add(cap);
        });

        List<CapabilityGroup> result = new ArrayList<>();
        byGroup.forEach((group, caps) -> result.add(new CapabilityGroup(group, caps)));
        return result;
    }

    public Set<String> expandToPermissionCodes(Set<String> selectedCapabilityCodes,
                                               List<CapabilityDefinitionDTO> declarations) {
        if (selectedCapabilityCodes == null || declarations == null) {
            return Set.of();
        }
        Map<String, CapabilityDefinitionDTO> byCode = new LinkedHashMap<>();
        declarations.stream()
                .filter(CapabilityDefinitionDTO::isValid)
                .forEach(d -> byCode.putIfAbsent(d.getCode(), d));

        Set<String> result = new LinkedHashSet<>();
        for (String capCode : selectedCapabilityCodes) {
            CapabilityDefinitionDTO d = byCode.get(capCode);
            if (d != null && d.getIncludes() != null) {
                result.addAll(d.getIncludes());
            }
        }
        return result;
    }

    /**
     * The full capability -&gt; permission-code map the {@link #resolve} view presents: declared
     * capabilities plus convention-derived ones ({@code module.resource} -&gt; that resource's
     * {@code module.resource.action} codes). This is the authority for writes: the capability
     * checklist is the only grant surface, so every capability it renders — declared OR
     * convention-derived — must resolve to the codes its checkbox grants/revokes. Callers feed the
     * role's COMPLETE desired selection (the editor seeds from what's granted), so applying it is a
     * full-state replace within this universe.
     */
    public Map<String, Set<String>> capabilityCodeMap(List<CapabilityDefinitionDTO> declarations,
                                                       List<String> allPermissionCodes) {
        List<CapabilityDefinitionDTO> decls = declarations == null ? List.of() : declarations;
        List<String> allCodes = allPermissionCodes == null ? List.of() : allPermissionCodes;

        LinkedHashMap<String, Set<String>> map = new LinkedHashMap<>();
        Set<String> covered = new HashSet<>();
        decls.stream()
                .filter(CapabilityDefinitionDTO::isValid)
                .forEach(d -> {
                    map.put(d.getCode(), new LinkedHashSet<>(d.getIncludes()));
                    covered.addAll(d.getIncludes());
                });
        for (String code : allCodes) {
            if (code == null || covered.contains(code)) {
                continue;
            }
            int firstDot = code.indexOf('.');
            int secondDot = firstDot < 0 ? -1 : code.indexOf('.', firstDot + 1);
            if (firstDot < 0 || secondDot < 0) {
                continue;
            }
            map.computeIfAbsent(code.substring(0, secondDot), k -> new LinkedHashSet<>()).add(code);
        }
        return map;
    }

    /** Action codes preferred (in order) when picking a representative permission name for a label. */
    private static final List<String> LABEL_ACTION_PRIORITY = List.of("manage", "admin", "read", "view", "use");

    /**
     * Business label for a convention-derived {@code module.resource} bundle: the localized name of
     * its most representative action (manage &gt; admin &gt; read &gt; …), else the raw resource
     * segment. Uses a whole real permission name (e.g. {@code Webhook管理}) rather than a
     * common-substring "noun", which is fragile across mixed-case / inconsistent names (e.g.
     * {@code Webhook管理} + {@code System webhook update} share only the fragment {@code ebhook}).
     */
    private String conventionLabel(List<String> includes, Map<String, String> names, String rawResource) {
        return representativeName(includes, names, rawResource);
    }

    /** Name of the highest-priority action that has a name, else the first available name. */
    private String representativeName(List<String> includes, Map<String, String> names, String rawResource) {
        String chosen = null;
        int bestRank = Integer.MAX_VALUE;
        for (String code : includes) {
            String name = names.get(code);
            if (name == null || name.isBlank()) {
                continue;
            }
            String action = code.substring(code.lastIndexOf('.') + 1);
            int rank = LABEL_ACTION_PRIORITY.indexOf(action);
            if (rank < 0) {
                rank = LABEL_ACTION_PRIORITY.size();
            }
            if (rank < bestRank) {
                bestRank = rank;
                chosen = name.trim();
            }
        }
        return chosen != null ? chosen : rawResource;
    }

    private String label(CapabilityDefinitionDTO d) {
        if (d.getNameZhCN() != null && !d.getNameZhCN().isBlank()) {
            return d.getNameZhCN();
        }
        if (d.getNameEn() != null && !d.getNameEn().isBlank()) {
            return d.getNameEn();
        }
        return d.getCode();
    }
}
