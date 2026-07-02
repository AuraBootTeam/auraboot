package com.auraboot.framework.application.security;



public class WhiteList {
    public static final String[] whiteList = {
            "/api/auth/login",
            "/api/auth/login/sms",
            "/api/auth/login/email-code",
            "/api/auth/login/channels",
            "/api/auth/login/social/**",
            "/api/auth/register",
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/auth/verify-code/**",

            "/api/bootstrap/**",

            "/api/health",
            "/api/public/**",
            "/api/i18n/**",

            // Plugin-contributed PUBLIC REST endpoints (gamma-3). The single unique prefix exposes
            // ONLY the /public/ subpath of any plugin namespace; the dispatcher binds a default-tenant
            // public context and rate-limits. Non-/public plugin routes stay authenticated.
            "/api/ext/*/public/**",

            // Anonymous behavior ingestion (SP2): keyed by a public site_key, tenant resolved
            // server-side from the key (never trusts the client). The authenticated /api/collect
            // stays JWT-only. Protected by KeyedCollectGuard (origin allowlist + per-key/per-IP
            // rate limit + batch cap), not by auth.
            "/api/collect/keyed",

            // Mobile config — no auth required (app needs config before login)
            "/api/mobile/config",
            "/api/mobile/capabilities",
            "/api/mobile/goods-ticket/**",

            "/api/ai/aurabot/oauth/callback",

            "/api/im/ws",

            "/api/reviews",
            "/api/reviews/summary",

            "/api/payment/webhook/**",
            "/api/marketplace/paid/webhooks/stripe",

            // Automation webhook receiver (no auth — controller enforces per-automation
            // signature/token validation and fails closed when none is configured)
            "/api/automations/webhooks/**",

            // CRM public inbound endpoint (no auth — adapters perform their own verification)
            "/api/crm/inbound/**",
            "/api/crm/forms/**",

            // CRM Calendar OAuth callback (no auth — state token carries identity)
            "/api/crm/calendar/callback/**",

            // Public shared views (GAP-121)
            "/api/views/shared/**",

            "/actuator/health",
            "/actuator/health/**",
            "/actuator/prometheus",
            "/actuator/info",

            // REG-3 (DDR-2026-06-30): anonymous A2A discovery (/.well-known/agent*) removed from the
            // whitelist — it exposed every tenant's agent metadata (name/description/skills) to
            // unauthenticated callers. The endpoints now require authentication and are tenant-scoped
            // in AgentCardService.
    };

    /** Swagger paths — only whitelisted when dev/test profile is active */
    public static final String[] swaggerWhiteList = {
            "/swagger-ui/**",
            "/v3/api-docs/**",
    };

    /** Test seed paths — only whitelisted when test profile is active */
    public static final String[] testWhiteList = {
            "/api/test/**",
    };
}
