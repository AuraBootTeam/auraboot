package com.auraboot.framework.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for AuraBoot's public-demo guardrails.
 *
 * <p>Activated via {@code aura.demo.enabled=true} (or {@code AURA_DEMO_MODE=true}
 * which the application context maps to the same property). When enabled, the
 * {@link DemoModeGuard} filter rejects requests matching any of the configured
 * deny patterns with HTTP 403, regardless of the caller's authentication state.
 *
 * <p>The default deny list targets operations a malicious public-demo visitor
 * could exploit: license issuance, plugin upload/install, schema migrations,
 * tenant destruction, password resets for other users, admin-only endpoints.
 * Operators can add or remove patterns via configuration.
 *
 * <p>Demo mode is intentionally a runtime-config concept, not a build flag —
 * the same image runs in both production and demo deployments, behavior
 * differs only by env. Banner copy is also config-driven so deployments can
 * customize the warning shown on the login page.
 */
@ConfigurationProperties(prefix = "aura.demo")
public class DemoModeProperties {

    /** Whether public-demo guardrails are active. */
    private boolean enabled = false;

    /** Banner text shown on the login page when demo mode is on. */
    private String banner = "This is a public demo — data is wiped every 30 minutes. Don't enter sensitive info.";

    /** Reset interval (informational; the actual reset is run by an external cron sidecar). */
    private int resetIntervalMin = 30;

    /**
     * URL patterns blocked in demo mode. Matched against request URI with
     * {@link org.springframework.util.AntPathMatcher} semantics.
     */
    private List<String> denyPaths = defaultDenyPaths();

    /** Allowlist exceptions (matched first; overrides denyPaths). */
    private List<String> allowPaths = new ArrayList<>();

    private static List<String> defaultDenyPaths() {
        List<String> paths = new ArrayList<>();

        // --- Verified-real OSS endpoints (controller exists in code) ---
        // Plugin package upload — real path is /api/plugins/packages/**, NOT
        // /api/plugins/upload (PluginPackageController @RequestMapping
        // "/api/plugins/packages"). Blocking the whole packages tree covers
        // upload + remove + activate.
        paths.add("/api/plugins/packages/**");
        paths.add("/api/plugins/*/install");
        paths.add("/api/plugins/*/uninstall");
        paths.add("/api/marketplace/install/**");
        paths.add("/api/marketplace/*/install");
        // Admin surfaces (verified — 18+ controllers under /api/admin/**)
        paths.add("/api/admin/**");
        // Auth-sensitive (verified — AuthController.forgotPassword + resetPassword)
        paths.add("/api/auth/forgot-password");
        paths.add("/api/auth/reset-password");
        paths.add("/api/users/*/password");
        // Test / fixture seeders (verified — TestSeedController etc.)
        paths.add("/api/test/**");
        paths.add("/api/_internal/**");

        // --- Preventive (no current controller maps these, but kept so a
        // future endpoint added under these paths is denied by default) ---
        paths.add("/api/license/**");          // license issuance is enterprise-only today
        paths.add("/api/admin/license/**");
        paths.add("/api/system/migrate");
        paths.add("/api/system/reset");
        paths.add("/api/system/danger/**");
        paths.add("/api/tenants/*/destroy");
        paths.add("/api/tenants/*/transfer-ownership");

        return paths;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBanner() { return banner; }
    public void setBanner(String banner) { this.banner = banner; }

    public int getResetIntervalMin() { return resetIntervalMin; }
    public void setResetIntervalMin(int resetIntervalMin) { this.resetIntervalMin = resetIntervalMin; }

    public List<String> getDenyPaths() { return denyPaths; }
    public void setDenyPaths(List<String> denyPaths) { this.denyPaths = denyPaths; }

    public List<String> getAllowPaths() { return allowPaths; }
    public void setAllowPaths(List<String> allowPaths) { this.allowPaths = allowPaths; }
}
