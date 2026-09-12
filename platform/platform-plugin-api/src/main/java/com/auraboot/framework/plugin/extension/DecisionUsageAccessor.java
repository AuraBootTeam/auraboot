package com.auraboot.framework.plugin.extension;

import java.util.List;
import java.util.Map;

/**
 * Public write port for product-owned sources that participate in DecisionOps impact analysis.
 * The platform owns the projection table and reference parsing; products own source traversal.
 */
public interface DecisionUsageAccessor {

    void replaceSource(Source source, List<Fragment> fragments);

    void deleteSource(String sourceType, String sourcePid);

    record Source(String type, String code, String version, String pid) {
        public Source {
            type = type == null ? "" : type.trim().toUpperCase();
            code = code == null ? "" : code;
            version = version == null ? "" : version;
            pid = pid == null ? "" : pid;
        }
    }

    record Fragment(Object content, String binding, String targetPath, Map<String, Object> metadata) {
        public Fragment {
            binding = binding == null ? "" : binding;
            targetPath = targetPath == null ? "" : targetPath;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
