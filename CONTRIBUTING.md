# Contributing to AuraBoot

Thank you for your interest in contributing to AuraBoot! This guide covers everything you need to get started.

## Code of Conduct

All participants are expected to follow our [Code of Conduct](CODE_OF_CONDUCT.md). Please read it before contributing.

---

## Development Setup

### Prerequisites

| Tool       | Version | Notes                          |
|------------|---------|--------------------------------|
| Java (JDK) | 21+     | GraalVM or Temurin recommended |
| Node.js    | 20+     | Active LTS; matches README + CI |
| PostgreSQL | 15+     | Default port 5432; Docker stack ships PG 16; pgvector required for AI features |
| Redis      | 7+      | Default port 6379              |
| pnpm       | 9+      | `npm install -g pnpm`          |
| Git        | 2.30+   |                                |

### Getting Started

```bash
# 1. Fork and clone
git clone https://github.com/<your-username>/auraboot.git
cd auraboot

# 2. Initialize the database
./scripts/oss-reset-and-init.sh

# 3. Start the backend (Spring Boot on port 6443)
cd platform && ./gradlew bootRun

# 4. In a separate terminal, start the frontend (Vite + BFF on port 5173)
cd web-admin && pnpm install && pnpm dev:full
```

Open [http://localhost:5173](http://localhost:5173) in your browser.

**Test account:** `admin@auraboot.com` / `Test2026x`

### Verifying Your Setup

```bash
# Backend health check
curl -s http://localhost:6443/actuator/health

# Run backend tests
cd platform && ./gradlew test

# Run AI runtime regression tests (AuraBot / Agent / RAG / Intent)
cd platform && ./gradlew testAi

# Run E2E tests
cd web-admin && NO_PROXY=localhost npx playwright test
```

`./gradlew test` covers the main backend suite, but AI runtime packages are intentionally isolated behind `./gradlew testAi` because they require a heavier Spring context and additional fixtures. If your change touches AuraBot, agents, RAG, intent routing, or shared AI infrastructure, run both commands before opening a PR.

---

## Code Style

### Java (Backend)

- Follow [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).
- Use Java 21 features (records, sealed classes, pattern matching) where appropriate.
- Use MyBatis-Plus mapper interfaces for data access. No `JdbcTemplate`.
- Always check table schema (`psql -c "\d table_name"`) before writing SQL.

### TypeScript (Frontend)

- ESLint and Prettier are configured in the project. Run `pnpm lint` before committing.
- Use TypeScript strict mode. Avoid `any` unless absolutely necessary.
- Use functional components with hooks. No class components.
- All user-facing text must be internationalized (i18n). No hardcoded strings.
- Business CRUD pages must use the DSL configuration system, not hand-coded TSX.

### Commit Messages

We use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add lead scoring to CRM module
fix: correct pagination parameter in dynamic controller
docs: update plugin development guide
test: add E2E tests for opportunity kanban
refactor: simplify command execution pipeline
chore: upgrade Spring Boot to 3.5.1
```

### Language Policy

| Artifact             | Language |
|----------------------|----------|
| Source code           | English  |
| Code comments         | English  |
| Git commits and PRs   | English  |
| API documentation     | English  |

---

## Pull Request Process

### Workflow

1. **Fork** the repository on GitHub.
2. **Create a branch** from `main` with a descriptive name:
   - `feature/add-inventory-module`
   - `fix/pagination-off-by-one`
   - `docs/update-api-reference`
   - `test/crm-lead-scoring-e2e`
3. **Make your changes** with tests and documentation.
4. **Run all tests** to ensure nothing is broken.
5. **Push** your branch and **open a Pull Request** against `main`.

For local frontend development, use `pnpm dev:full` in the foreground. For background mode, run `pnpm sync-plugins` first, then start `pnpm dev:web` and `pnpm dev:bff` as separate processes instead of `nohup pnpm dev:full`.

### PR Requirements

Every pull request must include:

1. **Description** — What changed and why. Link related issues with `Closes #123`.
2. **Test Plan** — How you verified the changes work correctly.
3. **Tests** — Backend changes need integration tests. UI changes need E2E tests.
4. **No Broken Tests** — All existing tests must pass.

### Review Process

1. A maintainer will review your PR, usually within a few business days.
2. Address feedback by pushing additional commits to your branch.
3. Once approved, a maintainer will merge your PR.

### Tips for a Great PR

- Keep it small and focused — one concern per PR.
- Test both happy path and edge cases.
- Write a clear description so reviewers understand the "why" without reading every line.
- Squash fixup commits before requesting review.

---

## Issue Guidelines

### Bug Reports

Use the [Bug Report template](https://github.com/AuraBootTeam/auraboot/issues/new?template=bug_report.yml) and include:

- A clear description of the problem
- Steps to reproduce
- Expected vs. actual behavior
- Environment details (OS, browser, Java version, AuraBoot version)

### Feature Requests

Use the [Feature Request template](https://github.com/AuraBootTeam/auraboot/issues/new?template=feature_request.yml) and describe:

- The problem you are trying to solve
- Your proposed solution
- Alternatives you have considered

---

## Plugin Development

AuraBoot's plugin system lets you extend the platform through declarative JSON configuration.

```bash
npx @auraboot/plugin-cli create my-plugin
# Edit config files — add models, fields, commands, pages
npx @auraboot/plugin-cli import ./my-plugin
```

See the [Plugin Development Guide](docs/system-reference/plugins/02-插件开发指南.md) for details.

---

## Contributor License Agreement

AuraBoot is dual-licensed (the [source-available AuraBoot License](LICENSE.txt) for the community edition + a separate commercial license for enterprise customers). To distribute your contribution under both licenses, we need a signed [Contributor License Agreement (CLA)](CLA.md).

**On your first pull request**, the CLA Assistant bot will comment with a one-click sign-off instruction. You sign by replying:

> I have read the CLA Document and I hereby sign the CLA

The signature is recorded against your GitHub username and applies to all your future PRs.

**The CLA does not transfer your copyright** — you retain ownership of your work. You grant the project the rights needed to redistribute and sublicense it.

**Trivial contributions are exempt** (typo fixes, formatting). See [CLA.md § Trivial Contributions](CLA.md#trivial-contributions) for the full list.

For organizations, a Corporate CLA may be required if your employer holds rights to your work. Contact license@auraboot.com.

---

## Getting Help

- [Usage FAQ](USAGE-FAQ.md) — common questions about getting started, data, plugins, AI, and ops (check here first)
- [License FAQ](LICENSE-FAQ.md) / [License FAQ (English)](LICENSE-FAQ-en.md) — questions about commercial use, modifications, SaaS boundaries
- [GitHub Discussions](https://github.com/AuraBootTeam/auraboot/discussions) — open-ended questions and ideas
- [GitHub Issues](https://github.com/AuraBootTeam/auraboot/issues) — bug reports and feature requests
- [Discord](https://discord.gg/p2fW5A2MW6) — real-time community chat
- [Security](SECURITY.md) — vulnerability disclosure (don't open a public issue)
- [Telemetry](TELEMETRY.md) — what AuraBoot does and doesn't send across the network

## Architecture & deep references

- [Architecture overview](docs/architecture/overview.md) — system layering, request flow
- [Data model](docs/architecture/data-model.md) — schema conventions
- [Tech stack](docs/architecture/tech-stack.md) — Java / TypeScript / DB choices
- [Plugin development](docs/plugin-development/) — building your own plugins

## Documentation contributions

Docs PRs are especially welcome and have a faster review lane (we aim to first-respond within 24h on docs PRs during beta). The docs source of truth lives in `auraboot/docs/`; the public site at docs.auraboot.com is auto-synced via `scripts/sync-docs-to-website.sh`. If you're adding screenshots, see [docs/community/readme-screenshots-spec.md](docs/community/readme-screenshots-spec.md) for visual conventions.

Thank you for helping make AuraBoot better!
