package com.auraboot.framework.authoring.policy;

import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.CapabilityManifest;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.EffectTag;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PropertyCapability;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Reversibility;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.RiskLevel;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Route;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Core block capability manifests. Plugins extend the registry through a future provider SPI;
 * until then, an unknown plugin block is deliberately denied rather than guessed from UI schema.
 */
@Component
public class CoreAuthoringCapabilityRegistry implements AuthoringCapabilityRegistry {

    private static final String PLUGIN_CODE = "core.designer";
    private static final String PLUGIN_VERSION = "0.1.0";
    private static final String MANIFEST_VERSION = "1";
    public static final String REORDER_WITHIN_PARENT_PATH = "/$structure/order";
    private static final Set<PatchOperation> VALUE_OPERATIONS =
            Set.copyOf(EnumSet.of(PatchOperation.ADD, PatchOperation.REPLACE, PatchOperation.REMOVE));

    private final Map<String, CapabilityManifest> manifests;
    private final String registryChecksum;

    public CoreAuthoringCapabilityRegistry() {
        Map<String, Map<String, PropertyCapability>> definitions = new LinkedHashMap<>();

        definitions.put("field", properties(
                commonTitle(), commonSpan(), reorderWithinParent(),
                property("/props/label", Route.INLINE, RiskLevel.L1,
                        effects(EffectTag.PRESENTATION), Reversibility.REVERSIBLE, false, false),
                property("/props/visible", Route.GUIDED_INLINE, RiskLevel.L2,
                        effects(EffectTag.VISIBILITY), Reversibility.REVERSIBLE, false, true),
                property("/props/required", Route.HANDOFF_STUDIO, RiskLevel.L3,
                        effects(EffectTag.BUSINESS_ACTION), Reversibility.REVERSIBLE, false, true)));

        definitions.put("column", definitions.get("field"));
        definitions.put("list", listProperties());
        definitions.put("table", listProperties());

        definitions.put("action", properties(
                commonTitle(), commonSpan(), reorderWithinParent(),
                property("/props/label", Route.GUIDED_INLINE, RiskLevel.L2,
                        effects(EffectTag.PRESENTATION),
                        Reversibility.REVERSIBLE, true, true),
                property("/props/icon", Route.INLINE, RiskLevel.L1,
                        effects(EffectTag.PRESENTATION), Reversibility.REVERSIBLE, false, false),
                property("/props/variant", Route.GUIDED_INLINE, RiskLevel.L2,
                        effects(EffectTag.PRESENTATION),
                        Reversibility.REVERSIBLE, true, true),
                property("/props/visibleWhen", Route.GUIDED_INLINE, RiskLevel.L2,
                        effects(EffectTag.VISIBILITY),
                        Reversibility.REVERSIBLE, false, true),
                property("/props/targetPage", Route.GUIDED_INLINE, RiskLevel.L2,
                        effects(EffectTag.NAVIGATION), Reversibility.REVERSIBLE, false, true),
                property("/props/commandCode", Route.HANDOFF_STUDIO, RiskLevel.L3,
                        effects(EffectTag.BUSINESS_ACTION), Reversibility.REVERSIBLE, false, true)));

        definitions.put("chart", properties(
                commonTitle(), commonSpan(), reorderWithinParent(),
                property("/props/height", Route.INLINE, RiskLevel.L0,
                        effects(EffectTag.PRESENTATION), Reversibility.REVERSIBLE, false, false),
                property("/dataSource", Route.HANDOFF_STUDIO, RiskLevel.L3,
                        effects(EffectTag.DATA_BINDING), Reversibility.REVERSIBLE, false, true)));

        definitions.put("description", contentProperties());
        definitions.put("rich-text", contentProperties());
        definitions.put("tabs", properties(
                commonTitle(), commonSpan(), reorderWithinParent(),
                property("/props/defaultTab", Route.INLINE, RiskLevel.L1,
                        effects(EffectTag.PRESENTATION), Reversibility.REVERSIBLE, false, false)));

        definitions.put("filter-field", definitions.get("field"));
        definitions.put("form", layoutProperties());
        definitions.put("detail", layoutProperties());
        definitions.put("dashboard", layoutProperties());
        definitions.put("form-section", layoutProperties());
        definitions.put("detail-section", layoutProperties());
        definitions.put("tab", layoutProperties());
        definitions.put("filter-bar", layoutProperties());
        definitions.put("action-bar", layoutProperties());
        definitions.put("widget", layoutProperties());
        definitions.put("stat-card", layoutProperties());

        Map<String, CapabilityManifest> built = new LinkedHashMap<>();
        definitions.forEach((blockType, propertyMap) -> {
            String checksum = checksum(blockType, propertyMap);
            built.put(blockType, new CapabilityManifest(blockType, PLUGIN_CODE, PLUGIN_VERSION,
                    MANIFEST_VERSION, checksum, propertyMap));
        });
        manifests = Map.copyOf(built);
        registryChecksum = registryChecksum(built.values());
    }

    @Override
    public Optional<CapabilityManifest> find(String blockType) {
        return Optional.ofNullable(manifests.get(blockType));
    }

    @Override
    public Collection<CapabilityManifest> all() {
        return manifests.values();
    }

    @Override
    public String checksum() {
        return registryChecksum;
    }

    private Map<String, PropertyCapability> listProperties() {
        return properties(
                commonTitle(), commonSpan(), reorderWithinParent(),
                property("/props/density", Route.INLINE, RiskLevel.L0,
                        effects(EffectTag.PRESENTATION), Reversibility.REVERSIBLE, false, false),
                property("/props/pageSize", Route.INLINE, RiskLevel.L1,
                        effects(EffectTag.PRESENTATION), Reversibility.REVERSIBLE, false, false),
                property("/props/defaultSort", Route.GUIDED_INLINE, RiskLevel.L2,
                        effects(EffectTag.DEFAULT_FILTER), Reversibility.REVERSIBLE, false, true),
                property("/props/defaultFilter", Route.GUIDED_INLINE, RiskLevel.L2,
                        effects(EffectTag.DEFAULT_FILTER), Reversibility.REVERSIBLE, false, true),
                property("/dataSource", Route.HANDOFF_STUDIO, RiskLevel.L3,
                        effects(EffectTag.DATA_BINDING), Reversibility.REVERSIBLE, false, true));
    }

    private Map<String, PropertyCapability> contentProperties() {
        return properties(
                commonTitle(), commonSpan(), reorderWithinParent(),
                property("/props/content", Route.INLINE, RiskLevel.L1,
                        effects(EffectTag.PRESENTATION), Reversibility.REVERSIBLE, false, false));
    }

    private Map<String, PropertyCapability> layoutProperties() {
        return properties(commonTitle(), commonSpan(), reorderWithinParent());
    }

    private PropertyCapability commonTitle() {
        return property("/title", Route.INLINE, RiskLevel.L1,
                effects(EffectTag.PRESENTATION), Reversibility.REVERSIBLE, false, false);
    }

    private PropertyCapability commonSpan() {
        return property("/layout/span", Route.INLINE, RiskLevel.L0,
                effects(EffectTag.PRESENTATION), Reversibility.REVERSIBLE, false, false);
    }

    private PropertyCapability reorderWithinParent() {
        return new PropertyCapability(
                REORDER_WITHIN_PARENT_PATH,
                Set.of(PatchOperation.MOVE),
                Route.GUIDED_INLINE,
                RiskLevel.L1,
                effects(EffectTag.PRESENTATION),
                Reversibility.REVERSIBLE,
                false,
                false);
    }

    private PropertyCapability property(
            String path,
            Route route,
            RiskLevel risk,
            Set<EffectTag> effects,
            Reversibility reversibility,
            boolean protectedSemantic,
            boolean rolePreviewRequired) {
        return new PropertyCapability(path, VALUE_OPERATIONS, route, risk, effects, reversibility,
                protectedSemantic, rolePreviewRequired);
    }

    private Set<EffectTag> effects(EffectTag... tags) {
        return Set.copyOf(EnumSet.copyOf(List.of(tags)));
    }

    private Map<String, PropertyCapability> properties(PropertyCapability... capabilities) {
        Map<String, PropertyCapability> result = new LinkedHashMap<>();
        for (PropertyCapability capability : capabilities) {
            result.put(capability.propertyPath(), capability);
        }
        return Map.copyOf(result);
    }

    private String checksum(String blockType, Map<String, PropertyCapability> properties) {
        StringBuilder canonical = new StringBuilder()
                .append(PLUGIN_CODE).append('|')
                .append(PLUGIN_VERSION).append('|')
                .append(MANIFEST_VERSION).append('|')
                .append(blockType);
        properties.values().stream()
                .sorted(Comparator.comparing(PropertyCapability::propertyPath))
                .forEach(capability -> canonical
                        .append('|').append(capability.propertyPath())
                        .append('|').append(capability.allowedOperations().stream().sorted().toList())
                        .append('|').append(capability.route())
                        .append('|').append(capability.risk())
                        .append('|').append(capability.effectTags().stream().sorted().toList())
                        .append('|').append(capability.reversibility())
                        .append('|').append(capability.protectedSemantic())
                        .append('|').append(capability.rolePreviewRequired()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String registryChecksum(Collection<CapabilityManifest> registry) {
        String canonical = registry.stream()
                .sorted(Comparator.comparing(CapabilityManifest::blockType))
                .map(manifest -> manifest.blockType() + ':' + manifest.checksum())
                .reduce((left, right) -> left + '|' + right)
                .orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
