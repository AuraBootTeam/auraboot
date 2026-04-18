package com.auraboot.framework.permission.util;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bridges legacy permission codes and the newer system.* permission namespace.
 *
 * <p>The current tenant bootstrap template still seeds several legacy codes
 * while newer controllers and Playwright contracts already reference system.*
 * aliases. Expanding both sides here keeps runtime checks and /api/auth/me
 * consistent until the permission seed data is fully migrated.</p>
 */
public final class PermissionCodeAliasResolver {

    private static final Map<String, String> PRIMARY_TO_ALIAS = new LinkedHashMap<>();

    static {
        PRIMARY_TO_ALIAS.put("system.plugin.read", "plugin.plugin.read");
        PRIMARY_TO_ALIAS.put("system.plugin.update", "plugin.plugin.manage");
        PRIMARY_TO_ALIAS.put("system.meta_model.read", "meta.model.read");
        PRIMARY_TO_ALIAS.put("system.meta_model.update", "meta.model.manage");
        PRIMARY_TO_ALIAS.put("system.process.read", "workflow.process.manage");
        PRIMARY_TO_ALIAS.put("system.process.update", "workflow.process.manage");
        PRIMARY_TO_ALIAS.put("system.process.execute", "workflow.process.execute");
        PRIMARY_TO_ALIAS.put("system.process.admin", "workflow.process.admin");
        PRIMARY_TO_ALIAS.put("system.bpm_form.update", "bpm.form.manage");
        PRIMARY_TO_ALIAS.put("system.bpm_monitor.read", "bpm.monitor.read");
        PRIMARY_TO_ALIAS.put("system.bpm_monitor.update", "bpm.monitor.manage");
        PRIMARY_TO_ALIAS.put("system.bpm_config.update", "bpm.config.manage");
        PRIMARY_TO_ALIAS.put("system.bpm_task.read", "bpm.task.read");
        PRIMARY_TO_ALIAS.put("system.bpm_task.update", "bpm.task.manage");
        PRIMARY_TO_ALIAS.put("system.bpm_hook.update", "bpm.hook.manage");
        PRIMARY_TO_ALIAS.put("system.bpm_rule.update", "bpm.rule.manage");
        PRIMARY_TO_ALIAS.put("system.bpm_sla.update", "bpm.sla.manage");
        PRIMARY_TO_ALIAS.put("system.bpm_signature.update", "bpm.signature.manage");
        PRIMARY_TO_ALIAS.put("system.bpm_definition.update", "bpm.definition.manage");
        PRIMARY_TO_ALIAS.put("system.cloud_config.update", "cloud_config_manage");
        PRIMARY_TO_ALIAS.put("system.webhook.update", "sys.webhook.manage");
        PRIMARY_TO_ALIAS.put("system.print.generate", "print_generate");
    }

    private PermissionCodeAliasResolver() {}

    public static Set<String> resolveCandidates(String permissionCode) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (permissionCode == null || permissionCode.isBlank()) {
            return candidates;
        }

        candidates.add(permissionCode);

        String alias = PRIMARY_TO_ALIAS.get(permissionCode);
        if (alias != null) {
            candidates.add(alias);
            return candidates;
        }

        for (Map.Entry<String, String> entry : PRIMARY_TO_ALIAS.entrySet()) {
            if (entry.getValue().equals(permissionCode)) {
                candidates.add(entry.getKey());
                break;
            }
        }

        return candidates;
    }

    public static Set<String> expandCodes(Collection<String> permissionCodes) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        if (permissionCodes == null) {
            return expanded;
        }

        for (String permissionCode : permissionCodes) {
            expanded.addAll(resolveCandidates(permissionCode));
        }

        return expanded;
    }
}
