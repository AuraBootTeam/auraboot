# Changelog

All notable changes to AuraBoot will be documented in this file.

The format follows [Keep a Changelog 1.1](https://keepachangelog.com/en/1.1.0/),
and the project version follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

During the **0.x beta** period, minor versions may include backward-incompatible
changes. They are tagged as **BREAKING** below; consult the migration notes for
each before upgrading.

---

## [Unreleased]

### Added
- (entries here are folded into the next release)

### Changed

### Fixed

### Removed

### Security

---

## [0.1.0-beta.1] - 2026-05-11

First public beta of AuraBoot, a source-available AI-native business platform
with a declarative DSL engine, plugin architecture, workflow runtime, and
multi-tenant backend.

### Highlights
- DSL engine for JSON-defined models, generated Postgres tables, REST CRUD,
  list/detail pages, and audit logging.
- Configurable command pipeline for validation, permission checks, state
  transitions, handlers, side effects, and audit.
- AI-native core with AuraBot, Agent Control Plane, ChatBI/RAG components, and
  a unified LLM provider layer.
- SmartEngine-based BPMN workflow support with SLA handling and
  workflow-designer integration.
- PF4J backend plus Module Federation frontend plugin architecture, with 17
  public first-party plugin packages in the OSS scope.

### Added
- Public release-readiness gates for OSS boundary checks, docs validation,
  gitleaks scanning, CodeQL, Backend CI, Docker quickstart, and GHCR image
  publishing.
- Multi-arch GHCR backend images for `linux/amd64` and `linux/arm64`.
- Reconciled OSS scope metadata and public plugin inventory.
- Public community links, issue templates, CLA workflow handling, and Discord
  invite updates.
- Built-in plugin import cleanup so removed/internal demo directories are not
  imported by default.

### Changed
- License and repository metadata use the source-available community release
  framing.
- Docker cleanup-batch override no longer references the enterprise
  repository.
- CodeQL Java build compiles classes directly instead of running `assemble`,
  avoiding Gradle implicit dependency validation issues unrelated to CodeQL
  analysis.
- Docker quickstart smoke retries frontend startup and prints diagnostics on
  failure.

### Fixed
- Backend integration-test compilation after `BootstrapRepairService`
  constructor changes.
- CLA workflow behavior when the signatures token is not configured.
- Tag-triggered Gitee mirror workflow ref handling for detached checkouts.

### Security
- Gitleaks scan is green for the release commit.
- OSS boundary check is green for the release commit.
- CodeQL Java and JavaScript/TypeScript analyses are green for the release
  commit.

### Known issues
- Mobile UI feature coverage trails desktop and should be treated as beta.
- Page Designer is functional but still needs UX polish.
- No managed cloud offering is available yet.
- Public performance benchmark reporting is not yet part of this release.

---

<!--
Template — copy this block when cutting a release.

## [0.1.0-beta.1] - 2026-05-XX

Beta release notes lead with the headline so readers can decide whether
to upgrade in 5 seconds. Then a short paragraph framing what this
release is about. Then the structured sections.

### Highlights
- One-sentence headline per major item, linked to issue/PR.

### Added
- ✨ <feature>: <one-line description> (#PR)

### Changed
- ♻️ <change>: <one-line description> (#PR)

### Deprecated
- ⚠️ <thing>: deprecated, will be removed in 0.x.0. Migrate by ... (#PR)

### Removed
- 🗑 <thing>: removed (was deprecated since 0.x.0). (#PR)

### Fixed
- 🐛 <bug>: <one-line description> (#PR)

### Security
- 🔒 <CVE / advisory>: <one-line description>. Severity: <High|Medium|Low>. (#PR)

### Breaking changes
- 💥 <thing>: <description>. **Migration**: <one-line how to migrate>. (#PR)

### Known issues
- <issue>: workaround <how>. Tracked in #<issue>.

### Contributors
First-time contributors this release: @user1, @user2. Thanks!

[0.1.0-beta.1]: https://github.com/AuraBootTeam/auraboot/releases/tag/v0.1.0-beta.1
-->

---

## Conventions for maintainers

- Entries land in `[Unreleased]` as part of the PR that ships them.
- On release-cut, the section is renamed `[X.Y.Z] - YYYY-MM-DD` and a
  fresh `[Unreleased]` skeleton is added on top.
- Each release also gets a GitHub Release with the same body via
  `.github/workflows/release-notes.yml`.
- Use the icons (✨ ♻️ ⚠️ 🗑 🐛 🔒 💥) consistently — they make the
  changelog scannable.
- Link each entry to the PR or issue (`(#PR)`) so readers can audit
  changes without digging through git log.
- Always include `### Migration` notes for breaking changes — never
  ship a breaking change without telling users how to migrate.

## Release version policy

- **0.x.y-beta.N** — beta-tier; minor versions may include breaking
  changes (called out as `### Breaking changes`); patch versions never
  break compatibility.
- **0.x.y** — stable-tier in the 0.x series; patch versions never break
  compatibility, minor versions may break with deprecation notice.
- **1.0.0** — first stable; from then on, strict semver applies.

**No fixed 1.0 date during beta.** The 1.0 cut depends on the API surface
stabilizing (no `### Breaking changes` for ≥ 2 consecutive releases) and
the first commercial customer cohort validating production fitness. We
expect this around month 5–6 post-launch but won't promise it; "1.0
shipped because the calendar said so" is how you ship a bad 1.0. We'll
publish a target date once the conditions look met, then live with it.

## Release cadence

The team commits to **at least one release per month** during beta. Two
months without a release is the signal community treats as project death;
we don't go there.
