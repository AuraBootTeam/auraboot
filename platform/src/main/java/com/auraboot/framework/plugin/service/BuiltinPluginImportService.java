package com.auraboot.framework.plugin.service;

/**
 * Service for importing built-in plugins during tenant bootstrap.
 *
 * <p>Built-in plugins are shipped with the platform and grouped into two profiles
 * (Phase 3 of the bootstrap-unified plan):
 * <ul>
 *   <li><b>core</b> — always imported. Provides base RBAC / org / platform-admin
 *       capabilities required by every deployment ({@code org-management},
 *       {@code platform-admin}).</li>
 *   <li><b>demo</b> — opt-in only. Provides showcase / demo plugins
 *       ({@code core-meta}, {@code core-bpm}, {@code core-aurabot},
 *       {@code page-manager}, {@code crm-starter}, {@code showcase},
 *       {@code agent-control-plane}, {@code acp-showcase}, {@code workflow-demo}).
 *       Imported only when {@code includeDemoPlugins=true} (gated by the
 *       {@code AURABOOT_DEMO_SEED} env / {@code auraboot.bootstrap.demo-seed}
 *       property at startup, or {@code BootstrapRequest.seedDemoData=true} via
 *       the wizard).</li>
 * </ul>
 *
 * <p>Test-fixture plugins ({@code test-fixtures}) are NOT loaded by this service —
 * they are seeded out-of-band by the Playwright {@code 03-import-test-fixtures.spec.ts}
 * setup project and gated separately by {@code AURA_ENV=test}.
 */
public interface BuiltinPluginImportService {

    /**
     * Import core built-in plugins for a tenant. Demo plugins are NOT imported.
     * Equivalent to {@code importForTenant(tenantId, userId, false)}.
     *
     * @param tenantId tenant ID
     * @param userId   creator user ID
     */
    void importForTenant(Long tenantId, Long userId);

    /**
     * Import built-in plugins for a tenant, optionally including the demo profile.
     *
     * <p>Idempotent: subsequent invocations skip plugins already imported at the
     * same version (per-plugin {@code ab_plugin} version check) — repair-twice
     * produces no duplicate rows.
     *
     * @param tenantId          tenant ID
     * @param userId            creator user ID
     * @param includeDemoPlugins if {@code true}, also import the 9 demo profile
     *                          plugins; if {@code false}, only the 2 core plugins
     */
    void importForTenant(Long tenantId, Long userId, boolean includeDemoPlugins);
}
