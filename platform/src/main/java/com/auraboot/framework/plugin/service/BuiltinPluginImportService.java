package com.auraboot.framework.plugin.service;

/**
 * Service for importing built-in plugins when an explicit repair/admin flow
 * asks for it.
 *
 * <p>Built-in plugins are shipped with the platform and grouped into two profiles
 * (Phase 3 of the bootstrap-unified plan):
 * <ul>
 *   <li><b>core</b> — always imported. Provides base meta, BPM, AI-center,
 *       page-management, org, and platform-admin navigation required by every
 *       deployment ({@code core-meta}, {@code core-bpm}, {@code core-aurabot},
 *       {@code page-manager}, {@code org-management}, {@code platform-admin}).</li>
 *   <li><b>demo</b> — opt-in only. Provides showcase / demo plugins
 *       ({@code crm-starter}, {@code showcase}, {@code agent-control-plane},
 *       {@code workflow-demo}).
 *       Imported only when {@code includeDemoPlugins=true}.</li>
 * </ul>
 *
 * <p>The first-install {@code /api/bootstrap/setup} path does not call this
 * service. Reset/init scripts import plugins through {@code scripts/import-plugins.sh}
 * after minimal bootstrap completes.
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
     * @param includeDemoPlugins if {@code true}, also import the 4 demo profile
     *                           plugins; if {@code false}, only the 6 core plugins
     */
    void importForTenant(Long tenantId, Long userId, boolean includeDemoPlugins);
}
