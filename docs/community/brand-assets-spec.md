# Brand Assets — Capture Specification

> Companion to [`readme-screenshots-spec.md`](./readme-screenshots-spec.md). This
> covers logo / banner / OG image / favicon / social-platform avatars. Every
> platform we list in [`oss-launch-strategy.md`](../../../auraboot-website/docs/oss-launch-strategy.md#21-全平台账号注册)
> needs the same assets, and they all need to look identical.

## Asset matrix (build all of these once)

| Asset | Dimensions | File | Used on |
|---|---|---|---|
| Wordmark logo | SVG (scales) | `docs/assets/logo.svg` | README, website, all platforms |
| Mark only (icon) | 512×512 PNG + 1024×1024 PNG + SVG | `docs/assets/mark.svg` + `mark-512.png` + `mark-1024.png` | favicons, social avatars, app icon |
| Favicon | 32×32 ICO + 192×192 PNG | `web-admin/public/favicon.ico` + `apple-touch-icon.png` | browser tab |
| OG / Twitter card | 1200×630 PNG | `docs/assets/og-image.png` | meta tags on every page |
| GitHub social preview | 1280×640 PNG | uploaded to GitHub repo settings | github.com/AuraBootTeam/auraboot tile |
| Twitter banner | 1500×500 PNG | `docs/assets/twitter-banner.png` | profile banner |
| LinkedIn banner | 1584×396 PNG | `docs/assets/linkedin-banner.png` | profile banner |
| Discord banner | 960×540 PNG | (uploaded directly to Discord) | server banner |
| YouTube channel art | 2560×1440 PNG | `docs/assets/youtube-channel-art.png` | channel header |
| Bilibili 头图 | 1146×196 PNG | (uploaded directly) | space header |
| WeChat 公众号 头像 | 1024×1024 PNG | (uploaded directly) | OA avatar |
| Avatar (square, all platforms) | 512×512 PNG | `docs/assets/avatar.png` | every platform that has avatars |

## Brand consistency rules

- **One color palette**, two themes:
  - Primary: pick ONE accent color now and stick with it
  - Light theme background: pure white or very light gray (#F8F9FA)
  - Dark theme background: near-black (#0F172A) or true black
- **One typeface family** for the wordmark; pick one with both Latin + CJK glyphs (Inter, Source Sans, Noto Sans) so 中文 platforms can render the wordmark
- **One mark** — the icon should be recognizable at 16×16 (favicon) AND 1024×1024 (avatar). If it relies on detail to read, redesign.
- **Avoid trends that age fast**:
  - No 3D bevels / Aqua-style glossy buttons (2007 vibes)
  - No glassmorphism (2021 vibes)
  - Gradients OK if subtle and on-brand

## OG image content

The OG / Twitter card is the single most-shared visual representation of
the project. Treat it like a billboard.

**Required elements**:
- Wordmark
- 5-10 word tagline (current: "AI-native low-code business platform")
- One visual that hints at the product (e.g., the page designer or a
  schematic of the 20-stage pipeline)
- Subtle "Open source" / "Source-available" badge
- DO NOT include star count / contributor count (changes constantly)

**Layout suggestion**:

```
┌────────────────────────────────────────────────┐
│                                                │
│   [Wordmark]                                   │
│                                                │
│   AI-native low-code platform                  │
│   built on a 20-stage command pipeline         │
│                                                │
│   ┌────────────┐                               │
│   │  [pipeline │     Apache 2.0 + supplementary│
│   │  schematic]│     • Source-available        │
│   └────────────┘                               │
│                                                │
│   github.com/AuraBootTeam/auraboot             │
└────────────────────────────────────────────────┘
```

## Avatar / square crop

Square avatars must be readable at:
- 16×16 (favicon, browser tab)
- 32×32 (notification badges)
- 96×96 (Discord, GitHub member tile)
- 512×512 (most social platforms)
- 1024×1024 (Apple touch icon)

**Test**: zoom out to 16×16 in your design tool. Does the avatar still
look like AuraBoot, or like a smudge? If the latter, simplify.

## Where to drop the assets

```
auraboot/
├── docs/assets/
│   ├── logo.svg                  ← canonical wordmark
│   ├── logo-dark.svg             ← dark-theme variant
│   ├── mark.svg                  ← canonical mark
│   ├── mark-512.png
│   ├── mark-1024.png
│   ├── avatar.png                ← square 512x512
│   ├── og-image.png              ← 1200x630
│   ├── twitter-banner.png        ← 1500x500
│   ├── linkedin-banner.png       ← 1584x396
│   └── youtube-channel-art.png   ← 2560x1440
├── web-admin/public/
│   ├── favicon.ico
│   ├── favicon.svg
│   ├── favicon-32x32.png
│   ├── favicon-192x192.png
│   └── apple-touch-icon.png
```

## After uploading to GitHub

1. **Repo > Settings > Social preview**: upload `og-image.png` (or
   `github-social-preview.png` if you want a GH-tile-specific variant).
2. **Repo > About** (top right): set description, website link
   (`https://www.auraboot.com`), topics (low-code, ai, dsl, plugin,
   bpmn, java, react, postgresql, source-available).
3. **Pin** the most important repos in the org page.
4. **Org > Customize your profile**: upload the same avatar across the
   organization profile.

## Verification

- [ ] Open `<repo-url>` and confirm the social-preview tile shows what
  you expect (Twitter / LinkedIn share unfurl test)
- [ ] Open every social-platform profile in incognito — does it look
  the same as the rest?
- [ ] Run `https://realfavicongenerator.net/favicon_checker` against
  the website — passes all targets?
- [ ] Resize avatar to 16×16 in browser tab — still recognizable?
