# Release Notes Template

> Used by `gh release create` and the (planned) `.github/workflows/release-notes.yml`
> automation. Copy this block, fill it in, paste into the GitHub Release body when
> cutting a tag.

---

## AuraBoot vX.Y.Z — `<one-line headline>`

Released YYYY-MM-DD.

`<2-3 sentence framing: what is this release primarily about? Who should care?
For 0.x betas, lead with "Beta release" so users set expectations.>`

### Highlights

- 🎯 **`<headline 1>`** — one-line; link to docs or PR
- 🎯 **`<headline 2>`** — one-line; link to docs or PR
- 🎯 **`<headline 3>`** — one-line; link to docs or PR

### What's new

#### ✨ Added
- `<feature>` (#PR by @user) — one-line description, link to docs

#### ♻️ Changed
- `<thing>` (#PR) — what changed and why

#### 🐛 Fixed
- `<bug>` (#PR) — what broke and how

#### 🔒 Security
- `<CVE / advisory>` (#PR) — severity, fix summary

### 💥 Breaking changes

> Skip this section if there are none.

#### `<change>` (#PR)

**What changed**: `<one-paragraph technical description>`

**Who's affected**: `<which users / configs / plugins>`

**Migration**: 
```diff
- old config / API call
+ new config / API call
```

**Deadline**: This change ships in vX.Y.Z but the previous behavior is
deprecated until vX.Y.Z+1 / vX+1.0.0; act before that date.

### Known issues

- `<issue>` — workaround: `<how>`. Tracked in #N.

### Upgrade

For Docker Compose users:
```bash
docker compose pull
docker compose --profile full up --build -d
```

For source builds:
```bash
git pull --tags
git checkout vX.Y.Z
./gradlew clean build
```

If migrating from vA.B.C — see [migration guide](./docs/upgrades/A.B.C-to-X.Y.Z.md).

### Contributors

First-time contributors this release: `@u1`, `@u2`, `@u3`.

Returning contributors: `@u4`, `@u5`. Thanks!

### Links

- Full changelog: [vA.B.C...vX.Y.Z](https://github.com/AuraBootTeam/auraboot/compare/vA.B.C...vX.Y.Z)
- Discussions: [Release discussion thread](https://github.com/AuraBootTeam/auraboot/discussions)
- Discord: [Discord](https://discord.gg/p2fW5A2MW6)
- Feedback and migration questions: [website contact form](https://www.auraboot.com/contact?interest=feedback)

---

## Author checklist (delete before publishing)

- [ ] Headline is concrete, not "various improvements"
- [ ] Each highlight links to docs or PR
- [ ] Breaking changes have migration steps with diff
- [ ] CHANGELOG.md updated with same content
- [ ] Tagged with annotated tag: `git tag -a vX.Y.Z -m "AuraBoot vX.Y.Z"`
- [ ] Tag pushed: `git push origin vX.Y.Z` (triggers build-image workflow → GHCR)
- [ ] GHCR image confirmed published before announcing
- [ ] Twitter / GitHub Discussions / WeChat OA cross-posted within 4h of release
- [ ] First-time contributors @-tagged in the discussion thread
