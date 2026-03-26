<p align="center">
  <img src="docs/assets/logo.png" alt="AuraBoot Logo" width="120" />
  <h1 align="center">AuraBoot</h1>
  <p align="center"><strong>The open-source enterprise application platform powered by DSL</strong></p>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0-blue.svg" alt="License"></a>
  <a href="#"><img src="https://img.shields.io/badge/Build-passing-brightgreen.svg" alt="Build Status"></a>
  <a href="https://github.com/AuraBoot/auraboot/stargazers"><img src="https://img.shields.io/github/stars/AuraBoot/auraboot?style=social" alt="GitHub Stars"></a>
  <a href="https://discord.gg/auraboot"><img src="https://img.shields.io/discord/auraboot?label=Discord&logo=discord" alt="Discord"></a>
</p>

---

## What is AuraBoot?

AuraBoot is a DSL-driven enterprise application platform. Define data models, design pages visually, and configure business logic through a 20-stage command pipeline — no scaffolding, no boilerplate. A plugin ecosystem of 27+ modules makes every deployment extensible without touching core code.

## Core Features

- **DSL Engine** — Model, Field, Command, Page, Formula: define once and get database schema, REST API, and UI automatically.
- **20-Stage Command Pipeline** — A declarative execution pipeline covering validation, permission, state checks, side effects, webhooks, and audit in one unified flow.
- **Plugin Marketplace** — CLI + Marketplace + SDK. Build, publish, and install plugins that add models, pages, commands, and menus — all through JSON configuration.
- **AI Agent (AuraBot)** — Natural language data operations, in-app copilot, and agent orchestration with 8+ LLM provider integrations.
- **Multi-Database & Multi-Tenant** — PostgreSQL-native with row-level tenant isolation, RBAC, and resource/operation/data-level access control.
- **Self-Hosted** — Run on your own infrastructure with `docker compose up`. No vendor lock-in.

## Quick Start

```bash
git clone https://github.com/AuraBoot/auraboot.git
cd auraboot
docker compose up -d
```

Open [http://localhost:5173](http://localhost:5173) and log in with `admin@auraboot.test` / `Test2026x`.

### Manual Setup

```bash
# Prerequisites: Java 21+, Node.js 18+, PostgreSQL 14+, Redis

# 1. Initialize the database
./scripts/reset-and-init.sh

# 2. Start the backend (Spring Boot, port 6443)
cd platform && ./gradlew bootRun

# 3. Start the frontend (Vite + BFF, port 5173)
cd web-admin && npm run dev:full
```

## Tech Stack

| Layer      | Technology                                           |
|------------|------------------------------------------------------|
| Language   | Java 21, TypeScript                                  |
| Backend    | Spring Boot 3.5, MyBatis-Plus, PF4J                  |
| Frontend   | React 19, Tailwind CSS 4, Vite 6                     |
| Database   | PostgreSQL 14+                                       |
| Cache      | Redis                                                |
| BPM        | SmartEngine 3.7 (BPMN 2.0)                           |
| Testing    | JUnit 5, Playwright, JaCoCo                          |
| Container  | Docker, Docker Compose                               |

## Project Structure

```
auraboot/
  platform/               # Spring Boot backend
    src/main/java/         #   Application source code
    src/test/java/         #   Integration tests
  web-admin/               # React frontend + BFF
    app/                   #   Application source code
    tests/                 #   E2E and API tests
  plugins/                 # Plugin packages (27+ modules)
    crm/                   #   CRM plugin (L1)
    platform-admin/        #   Platform admin plugin
    ...
  docs/                    # Documentation
    system-reference/      #   Architecture and subsystem docs
  scripts/                 # Build, seed, and CI scripts
  docker-compose.yml       # One-command deployment
```

## Architecture

```
Frontend (React + Vite)  -->  BFF (Express)  -->  Backend (Spring Boot)  -->  PostgreSQL
       |                                                 |
 Page Designer                                  DSL Engine + AI Core
       |                                                 |
Plugin Marketplace                              Plugin Framework (PF4J)
```

**Command Execution Pipeline (20 Stages):**

```
LOAD -> SCHEMA_VALIDATE -> PERMISSION_CHECK -> IDEMPOTENCY_CHECK
  -> STATE_CHECK -> ASSERT -> LOCK -> PRE_MAPPING
  -> PRE_INVARIANT -> FIELD_MAP -> HANDLER -> API_CALL
  -> EFFECT -> SIDE_EFFECT -> WEBHOOK -> POST_MAPPING
  -> POST_INVARIANT -> NOTIFICATION -> AUDIT -> COMPLETED
```

## Community & Enterprise

| Feature                     | Community | Enterprise |
|-----------------------------|:---------:|:---------:|
| DSL Engine + Page Designer  | Yes       | Yes       |
| AI Copilot (AuraBot)        | Yes       | Yes       |
| BPM Workflow                | Yes       | Yes       |
| Plugin Ecosystem            | Yes       | Yes       |
| Agent Orchestration         | --        | Yes       |
| IM (WebSocket)              | --        | Yes       |
| CRM / ERP Plugins           | --        | Yes       |
| Mobile Apps (iOS + Android)  | --        | Yes       |

## Contributing

We welcome contributions of all kinds. See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, coding standards, and the PR process.

## Community

- [GitHub Discussions](https://github.com/AuraBoot/auraboot/discussions) — Ask questions and share ideas
- [GitHub Issues](https://github.com/AuraBoot/auraboot/issues) — Report bugs or request features
- [Discord](https://discord.gg/auraboot) — Join the community chat

## License

AuraBoot is open-source software licensed under the [GNU Affero General Public License v3.0 (AGPL-3.0)](LICENSE).

---

<p align="center">Built with care by the <a href="https://github.com/AuraBoot">AuraBoot Team</a></p>
