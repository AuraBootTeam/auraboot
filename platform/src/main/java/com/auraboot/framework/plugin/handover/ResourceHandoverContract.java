package com.auraboot.framework.plugin.handover;

import java.util.*;

/** V2 is preview-only. Adding an execution capability requires a separate implementation. */
public record ResourceHandoverContract(String schemaVersion, String contractId, int revision, String mode,
        List<String> capabilities, PackageVersion source, List<PackageVersion> targets,
        List<Resource> resources, List<Reference> references) {
    public record PackageVersion(String pluginId, String version) {}
    public record Resource(String type, String code, String targetPluginId, String modelCode,
                           String fieldCode, String expectedDataType, String dictCode) {}
    public record Reference(String type, String code, PackageVersion provider) {}
    public ResourceHandoverContract {
        require("resource-handover.v2".equals(schemaVersion), "Unsupported handover schema");
        require(contractId != null && contractId.matches("[a-z][a-z0-9.-]{0,127}") && revision > 0, "Invalid contract identity");
        require("tenant-scoped".equals(mode) && List.of("preview").equals(capabilities), "Only tenant-scoped preview is supported");
        validatePackage(source);
        require(targets != null && !targets.isEmpty() && targets.size() <= 16, "Invalid targets");
        targets = List.copyOf(targets); capabilities = List.copyOf(capabilities);
        var targetIds = new HashSet<String>();
        var versions = new HashMap<String,String>(); versions.put(source.pluginId(),source.version());
        for (var target : targets) {
            validatePackage(target); versions.put(target.pluginId(),target.version());
            require(!target.pluginId().equals(source.pluginId()) && targetIds.add(target.pluginId()), "Duplicate or source target");
        }
        require(resources != null && !resources.isEmpty() && resources.size() <= 512, "Invalid resource inventory");
        resources = List.copyOf(resources);
        references = references == null ? List.of() : List.copyOf(references);
        require(references.size() <= 128, "Too many references");
        var keys = new HashSet<String>(); var usedTargets = new HashSet<String>(); var dicts = new HashSet<String>();
        for (var resource : resources) {
            require(resource != null && Set.of("field", "dict", "model_field_binding").contains(resource.type()), "Unsupported resource type");
            require(code(resource.code()) && keys.add(resource.type() + ":" + resource.code()), "Invalid or duplicate resource");
            require(targetIds.contains(resource.targetPluginId()), "Undeclared resource target");
            usedTargets.add(resource.targetPluginId());
            if (resource.type().equals("model_field_binding")) {
                require(code(resource.modelCode()) && code(resource.fieldCode())
                        && resource.code().equals(resource.modelCode() + "." + resource.fieldCode()), "Invalid binding identity");
                require(resource.expectedDataType() == null && resource.dictCode() == null, "Unexpected binding attributes");
            } else {
                require(resource.modelCode() == null && resource.fieldCode() == null, "Unexpected compound identity");
                if (resource.type().equals("field")) require(code(resource.expectedDataType()), "Field type is required");
                else {
                    require(resource.expectedDataType() == null && resource.dictCode() == null, "Unexpected dictionary attributes");
                    dicts.add(resource.code());
                }
                require(resource.dictCode() == null || code(resource.dictCode()), "Invalid field dictionary");
            }
        }
        require(usedTargets.equals(targetIds), "Unused target");
        for (var reference : references) {
            require(reference != null && "dict".equals(reference.type()) && code(reference.code()), "Invalid external reference");
            validatePackage(reference.provider());
            String priorVersion=versions.putIfAbsent(reference.provider().pluginId(),reference.provider().version());
            require(priorVersion==null || priorVersion.equals(reference.provider().version()), "Conflicting package versions");
            require(!reference.provider().pluginId().equals(source.pluginId())
                    && keys.add(reference.type() + ":" + reference.code()), "Reference overlaps a moved resource");
            dicts.add(reference.code());
        }
        for (var resource : resources) {
            if (resource.dictCode() != null) require(dicts.contains(resource.dictCode()), "Unresolved field dictionary");
            if (resource.type().equals("model_field_binding")) require(keys.contains("field:" + resource.fieldCode()), "Binding field is absent");
        }
    }
    private static boolean code(String text) { return text != null && text.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,254}"); }
    private static void validatePackage(PackageVersion value) {
        require(value != null && code(value.pluginId()) && value.version() != null
                && value.version().length() <= 64 && value.version().matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?"), "Exact package version is required");
    }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
