# Brand Assets

All icons are derived from the AuraBoot brand mark (gradient blue/purple "AB").

## Files

| File | Size | Purpose | Referenced by |
|------|------|---------|---------------|
| `favicon.ico` | 16 / 32 / 48 (multi-size) | Browser tab | `<link rel="icon">` (auto) |
| `favicon-16x16.png` | 16×16 | Hi-DPI tab fallback | `manifest.json` |
| `favicon-32x32.png` | 32×32 | Hi-DPI tab fallback | `manifest.json` |
| `apple-touch-icon.png` | 180×180 | iOS home-screen shortcut | `<link rel="apple-touch-icon">` (auto) |
| `android-chrome-192x192.png` | 192×192 | PWA icon, in-app brand mark | `manifest.json`, `<img>` in Header / AuthHeader / AuthSidebar / LandingIntro |
| `android-chrome-512x512.png` | 512×512 | PWA splash, store listing | `manifest.json` |
| `intro.png` | — | Landing-page hero illustration | `LandingIntro.tsx` |
| `avatar.jpeg` | — | Default user avatar fallback | `UserAvatar` component |

Naming follows the [realfavicongenerator](https://realfavicongenerator.net) convention.

## In-app usage

The header slot is a 32×32 square next to the "AuraBoot" wordmark, so the
inline `<img>` references the icon-only mark (`/android-chrome-192x192.png`),
**not** the horizontal wordmark logos under `auraboot-enterprise/logo/`.

## Asset provenance

The checked-in files in this directory are the OSS Web Admin source assets.
Do not source or regenerate them from an Enterprise build/composition output:
those directories are derived and may be garbage-collected. Brand updates
must start from the approved source artwork, regenerate the complete size set,
and commit the resulting files here.

## .gitignore note

Root `.gitignore` has a global `*.png` rule. The exception
`!web-admin/public/*.png` lets brand assets ship with the repo —
**any new PNG added here is committed by default**. Keep this directory
free of screenshots and ad-hoc images.

## Auth middleware

Static asset paths bypass the session middleware via an extension regex in
`app/middleware/sessionMiddlewareFactory.ts` (`STATIC_ASSET_EXT`), so logos
render correctly on `/login` and other unauthenticated routes.
