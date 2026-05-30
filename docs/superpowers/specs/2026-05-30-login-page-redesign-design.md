# Login Page Redesign (reference-aligned, AI-native copy)

Date: 2026-05-30
Scope: `auraboot/web-admin/app/auth/Login.tsx` body + `platform/src/main/resources/seed/i18n-base.json`

## Goal

Restyle the `/login` page body to match the user-provided offline reference
(`AuraBoot_登录页_·_离线版.html`), while replacing its generic "低代码平台"
copy and **fabricated** trust numbers (`2,000+ 团队 / 99.9% / SOC2`) with
truthful AI-native positioning. Keep ALL existing login functionality.

## Decisions (user-confirmed)

1. **Positioning copy = AI-native runtime** (most truthful differentiation)
   - Badge: `AI 原生 · 企业应用运行时`
   - H1: `把企业系统,建成 [AI 能直接操作] 的样子` — highlight word in solid brand color
   - Lead: `可视化建模、页面与流程编排,统一沉淀为命令能力——人来点、AI 也能调;一次配置,Web、移动端与小程序全端交付。`
2. **Trust bar → real product pillars**: `模型驱动 · 命令治理 · 插件交付`
3. **Card scope = keep everything, reskin only**: multi-channel tabs (conditional),
   email/password, SMS, email-code, remember-me, forgot-password, SSO/social, signup link.
4. **Highlight word = solid brand color** (`#4B3FE4`), not outlined stroke (Chinese small-size legibility).

## Layout (desktop 2-col, mobile single-col)

- Top bar: reuse existing `AuthHeader` (logo/lang/theme/login/signup) — NOT duplicated in page.
- Left (white): badge pill → bold H1 w/ brand highlight → lead → 4-row feature LIST
  (icon chip + title + desc, row dividers) → pillar bar (dot-separated).
  Features reuse real capabilities: 可视化页面设计器 / 灵活数据模型 / 工作流自动化 / AI Agent.
- Right (`#F7F7FB` panel + left border): card max 404px — 欢迎登录 + sub →
  conditional channel tabs → 52px inputs (brand focus ring) → password w/ eye toggle →
  remember/forgot → solid brand submit → `或` divider → full-width SSO button (oidc) +
  icon row for other socials → signup link.
- Brand color `#4B3FE4` scoped to this page via inline class tokens (no global theme change).

## i18n (13 new keys, zh-CN + en-US)

`auth.welcomeSub`, `auth.badge`, `auth.headline.pre/.em/.post`, `auth.lead`,
`auth.pillar.model/.command/.plugin`, `auth.ssoLogin`, `auth.or`,
`auth.showPassword`, `auth.hidePassword`. Existing `auth.feature.*` reused.

All new keys use the 3-arg `t(key, undefined, fallback)` form so the Chinese
fallback renders before the seed lands in a given environment — avoids the
`t(key) || fb` footgun where a missing key returns the key (non-empty) and the
`||` fallback never fires.

## Verification (real, browser)

- `tsc --noEmit`: 0 errors.
- Playwright (chromium) against dev `:5290`, 5 viewports/themes + a missing-key
  cache run proving fallbacks render with **zero raw `auth.*` leaks**.
- Asserted: badge/headline/lead/4 features/3 pillars/card subtitle present,
  password toggle toggles `type`, brand colors (`rgb(75,63,228)`), right-panel
  `rgb(247,247,251)`, multichannel tabs + full-width SSO (`使用企业 SSO 登录`) + wechat icon.
- Screenshots: `~/Downloads/login-redesign-shots/` (zh light/dark 1440, 1920, mobile 390, en 1440, multichannel, fallback).

## Out of scope

No changes to loader/action/auth handlers, AuthHeader, or global theme.
E2E specs unaffected (they key off `#email`/`#password`/`立即登录`/tabs/`#remember`).
