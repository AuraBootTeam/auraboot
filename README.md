<p align="center">
  <h1 align="center">AuraBoot</h1>
  <p align="center"><strong>AI-native low-code business platform — source-available, self-hosted</strong></p>
</p>

<p align="center">
  <a href="LICENSE.txt"><img src="https://img.shields.io/badge/License-AuraBoot_v1.1-blue.svg" alt="License"></a>
  <a href="https://github.com/AuraBootTeam/auraboot/actions/workflows/backend.yml"><img src="https://github.com/AuraBootTeam/auraboot/actions/workflows/backend.yml/badge.svg?branch=main" alt="Backend CI"></a>
  <a href="https://github.com/AuraBootTeam/auraboot/actions/workflows/frontend.yml"><img src="https://github.com/AuraBootTeam/auraboot/actions/workflows/frontend.yml/badge.svg?branch=main" alt="Frontend CI"></a>
  <a href="#"><img src="https://img.shields.io/badge/Java-21-orange.svg" alt="Java 21"></a>
  <a href="#"><img src="https://img.shields.io/badge/Spring_Boot-3.5-green.svg" alt="Spring Boot 3.5"></a>
  <a href="#"><img src="https://img.shields.io/badge/React-19-blue.svg" alt="React 19"></a>
  <a href="#"><img src="https://img.shields.io/badge/PostgreSQL-16-336791.svg" alt="PostgreSQL"></a>
  <a href="https://github.com/AuraBootTeam/auraboot/stargazers"><img src="https://img.shields.io/github/stars/AuraBootTeam/auraboot?style=social" alt="GitHub Stars"></a>
  <a href="https://discord.gg/auraboot"><img src="https://img.shields.io/badge/Discord-Join-7289da?logo=discord&logoColor=white" alt="Discord"></a>
</p>

<p align="center">
  <a href="#quick-start">Quick Start</a> •
  <a href="#key-features">Features</a> •
  <a href="#architecture">Architecture</a> •
  <a href="https://docs.auraboot.com">Docs</a> •
  <a href="#community">Community</a>
</p>

---

## What is AuraBoot?

AuraBoot is an open-source platform for building business applications using a declarative DSL (Domain-Specific Language) instead of writing boilerplate code. Define your data models, pages, commands, and workflows in JSON — the platform generates the database schema, REST APIs, and UI automatically. AI capabilities are built into the core: an in-app copilot, agent orchestration, ChatBI, and a RAG knowledge base that work with multiple LLM providers.

<!-- Screenshots (dashboard, page designer, command pipeline, AI copilot) land
     with v0.1.0-beta.1; capture spec at docs/community/readme-screenshots-spec.md -->

## Key Features

### DSL Engine
Define models, fields, commands, pages, and formulas in declarative JSON. A single model definition creates the database table, REST endpoints, form validation, and list/detail pages with no code generation step.

### 20-Stage Command Pipeline
Every data operation flows through a unified pipeline: schema validation → permission check → state machine → field mapping → handler → side effects → webhooks → audit. Fully configurable per command through DSL.

### 3 Visual Designers
- **Page Designer** — Drag-and-drop page builder with 20+ block types (forms, tables, charts, dashboards)
- **BPMN Designer** — Visual workflow editor with human tasks, SLA monitoring, and approval routing
- **Automation Designer** — Event-driven automation rules with triggers, conditions, and actions

### AI Full Stack
- **AuraBot** — In-app AI assistant for natural language queries, data operations, and guided workflows
- **Agent Control Plane (ACP)** — Orchestrate AI agents with skills, tools, and memory
- **ChatBI** — Ask questions about your data in natural language, get charts and tables back
- **RAG Knowledge Base** — Upload documents (PDF, DOCX, MD, CSV), vector-indexed for AI retrieval
- **Multi-LLM** — OpenAI, Anthropic, Zhipu GLM, MiniMax, and more through a unified provider interface

### BPM Workflow Engine
SmartEngine-based BPMN 2.0 engine with visual process design, human task assignment, approval inbox, escalation rules, and SLA tracking.

### Plugin System
PF4J-based plugin architecture. The OSS repo ships 16 first-party plugins (CRM, HR, BPM, asset management, AI / agent control plane, dashboards, etc.). Plugins are declarative JSON packages that add models, fields, commands, pages, and menus. Install from a marketplace or build your own with the CLI.

### Multi-Tenant RBAC
Row-level tenant isolation, role-based access control at resource/operation/data levels, and a complete permission system with menus, routes, and API-level enforcement.

### Notifications & Integration
Multi-channel notifications (email, in-app, webhook), event bus for cross-module communication, and webhook dispatch for external integrations.

## Quick Start

### Docker (recommended)

```bash
git clone https://github.com/AuraBootTeam/auraboot.git
cd auraboot
docker compose up -d
```

Open [http://localhost:5173](http://localhost:5173) and log in:

| | |
|---|---|
| **Email** | `admin@example.com` |
| **Password** | `Test2026x` (change immediately on first login) |

### Manual Setup

**Prerequisites:** Java 21+, Node.js 20+, PostgreSQL 15+, Redis 7+

```bash
# 1. Clone the repository
git clone https://github.com/AuraBootTeam/auraboot.git
cd auraboot

# 2. Initialize the database
./scripts/oss-reset-and-init.sh

# 3. Start the backend (Spring Boot, port 6443)
cd platform
./gradlew bootRun

# 4. In a new terminal — start the frontend (Vite + BFF, port 5173)
cd web-admin
pnpm install
pnpm dev:full
```

Open [http://localhost:5173](http://localhost:5173) in your browser.

`pnpm dev:full` is the default foreground developer entrypoint. If you need the frontend in background mode, run `pnpm sync-plugins` once and then start `pnpm dev:web` and `pnpm dev:bff` separately.

### Verify Your Setup

```bash
# Backend baseline tests
cd platform
./gradlew test

# AI runtime regression tests (AuraBot / Agent / RAG / Intent)
./gradlew testAi

# Frontend E2E smoke
cd ../web-admin
NO_PROXY=localhost npx playwright test
```

If you are working on AI features, run both `test` and `testAi`. The AI stack lives in core, but its regression suite is split into a dedicated Gradle task so it can run with a heavier test profile without slowing every default backend run.

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21, TypeScript |
| Backend | Spring Boot 3.5, MyBatis-Plus, PF4J |
| Frontend | React 19, Tailwind CSS 4, React Router 7, Vite 6 |
| Database | PostgreSQL 15+ (with pgvector) |
| Cache | Redis 7+ |
| BPM | SmartEngine 3.7 (BPMN 2.0) |
| AI | Multi-provider LLM integration (OpenAI, Anthropic, Zhipu, etc.) |
| Testing | JUnit 5, Playwright, JaCoCo |
| Observability | OpenTelemetry, Sentry, structured logging |
| Deployment | Docker, Docker Compose |

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Frontend (React)                         │
│  Page Designer │ BPMN Designer │ Automation Designer │ AuraBot  │
└──────────────────────────┬──────────────────────────────────────┘
                           │ BFF (Express)
┌──────────────────────────▼──────────────────────────────────────┐
│                     Spring Boot Backend                         │
│                                                                 │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────────┐ │
│  │  DSL Engine  │  │  AI Core     │  │  BPM Engine            │ │
│  │  Model       │  │  AuraBot     │  │  SmartEngine (BPMN)    │ │
│  │  Field       │  │  ACP         │  │  Human Tasks           │ │
│  │  Command     │  │  ChatBI      │  │  SLA Monitoring        │ │
│  │  Page        │  │  RAG / KB    │  │  Approval Inbox        │ │
│  │  Formula     │  │  Multi-LLM   │  │                        │ │
│  └──────┬──────┘  └──────────────┘  └────────────────────────┘ │
│         │                                                       │
│  ┌──────▼──────────────────────────────────────────────────┐   │
│  │              20-Stage Command Pipeline                   │   │
│  │  LOAD → VALIDATE → PERMISSION → STATE → LOCK → HANDLER │   │
│  │  → EFFECT → SIDE_EFFECT → WEBHOOK → AUDIT → COMPLETED  │   │
│  └─────────────────────────────────────────────────────────┘   │
│         │                                                       │
│  ┌──────▼──────┐  ┌──────────────┐  ┌────────────────────────┐ │
│  │  Plugin FW  │  │  RBAC        │  │  Notification          │ │
│  │  (PF4J)     │  │  Multi-Tenant│  │  Email/Webhook/In-App  │ │
│  └─────────────┘  └──────────────┘  └────────────────────────┘ │
└──────────────────────────┬──────────────────────────────────────┘
                           │
              ┌────────────▼────────────┐
              │  PostgreSQL  │  Redis   │
              └─────────────────────────┘
```

## Project Structure

```
auraboot/
├── platform/                 # Spring Boot backend
│   └── src/
│       ├── main/java/        #   Application source
│       └── test/java/        #   Integration tests
├── web-admin/                # React frontend + BFF
│   ├── app/                  #   Application source
│   └── tests/                #   E2E and API tests
├── plugins/                  # Plugin packages (16 first-party in OSS repo)
│   ├── crm/                  #   CRM plugin
│   ├── sales/                #   Sales management
│   ├── procurement/          #   Procurement
│   └── ...
├── docs/                     # Documentation (54 files)
│   ├── getting-started/      #   Quick start and tutorials
│   ├── core-concepts/        #   DSL, models, commands, pages, permissions
│   ├── guides/               #   Feature how-to guides
│   ├── use-cases/            #   15 industry solution walkthroughs
│   ├── plugin-development/   #   Plugin development guides
│   ├── api-reference/        #   REST API documentation
│   ├── deployment/           #   Docker, K8s, configuration
│   └── architecture/         #   System design and data model
├── scripts/                  # Build, seed, and CI scripts
├── docker/                   # Docker configuration
└── docker-compose.yml        # One-command infrastructure
```

## Documentation

**[Full Documentation →](docs/README.md)**

### Getting Started
- [Introduction](docs/getting-started/introduction.md) — What is AuraBoot, who it's for
- [OSS Onboarding](docs/community/getting-started.md) — Clone, bootstrap, and smoke-test the open-source core
- [Open-Source Scope](docs/community/oss-scope.md) — What is and isn't OSS, how to verify, how to adjust scope
- [Quick Start](docs/getting-started/quick-start.md) — Docker Compose setup in 5 minutes
- [Installation](docs/getting-started/installation.md) — Detailed installation guide
- [Build Your First App](docs/getting-started/first-app.md) — 30-minute tutorial

### Core Concepts
- [DSL Engine](docs/core-concepts/dsl-engine.md) — Declarative configuration philosophy
- [Models & Fields](docs/core-concepts/models-and-fields.md) — 22 field types, relations, formulas
- [Commands](docs/core-concepts/commands.md) — 20-stage pipeline reference
- [Pages & Layouts](docs/core-concepts/pages-and-layouts.md) — Page kinds, blocks, designers
- [Permissions](docs/core-concepts/permissions.md) — RBAC, multi-tenant, data-level security
- [State Machines](docs/core-concepts/state-machines.md) — Status flows and transitions

### Use Cases & Industry Solutions
- [CRM](docs/use-cases/crm.md) — Customer relationship management
- [Project Management](docs/use-cases/project-management.md) — Projects, tasks, Gantt charts
- [Sales](docs/use-cases/sales.md) — Pipeline, quoting, orders
- [Procurement](docs/use-cases/procurement.md) — Purchase orders, supplier management
- [Manufacturing](docs/use-cases/manufacturing.md) — Production planning, BOM, MRP
- [Warehouse](docs/use-cases/warehouse.md) — Inventory, WMS, stock management
- [Finance](docs/use-cases/finance.md) — Invoicing, AP/AR, tax compliance
- [And 8 more →](docs/use-cases/README.md)

### Extend & Deploy
- [Plugin Development](docs/plugin-development/overview.md) — Build plugins with JSON, Java, or React
- [API Reference](docs/api-reference/rest-api.md) — REST APIs, commands, data sources, webhooks
- [Deployment](docs/deployment/docker.md) — Docker, Kubernetes, configuration
- [Architecture](docs/architecture/overview.md) — System design and tech stack

## Community & Enterprise

| Capability | Community (Free) | Enterprise |
|---|:---:|:---:|
| DSL Engine + Page Designer | ✓ | ✓ |
| 20-Stage Command Pipeline | ✓ | ✓ |
| AI Copilot (AuraBot) | ✓ | ✓ |
| BPM Workflow Engine | ✓ | ✓ |
| Plugin System + CLI | ✓ | ✓ |
| Multi-Tenant RBAC | ✓ | ✓ |
| Agent Orchestration (ACP) | — | ✓ |
| IM (Real-time Messaging) | — | ✓ |
| CRM / ERP Plugin Suite | — | ✓ |
| Mobile Apps (iOS + Android) | — | ✓ |
| Priority Support + SLA | — | ✓ |

For enterprise licensing, contact [license@auraboot.com](mailto:license@auraboot.com) or visit [auraboot.com](https://www.auraboot.com).

## Community

- [GitHub Discussions](https://github.com/AuraBootTeam/auraboot/discussions) — Ask questions and share ideas
- [GitHub Issues](https://github.com/AuraBootTeam/auraboot/issues) — Report bugs or request features
- [Discord](https://discord.gg/auraboot) — Join the community chat

## Contributing

We welcome contributions of all kinds — bug fixes, features, documentation, and plugin development. See [CONTRIBUTING.md](CONTRIBUTING.md) for the development setup, coding standards, and PR process.

## Security

To report a security vulnerability, please email [security@auraboot.com](mailto:security@auraboot.com). Do not open a public issue. See [SECURITY.md](SECURITY.md) for our full security policy.

## License

AuraBoot is released under the [AuraBoot License v1.1](LICENSE.txt), a source-available license based on Apache 2.0 with supplementary terms.:

- **Free for internal use** — Use, modify, and deploy for your own business applications. No obligation to open-source your modifications.
- **Free for ISV / project delivery** — Build and deliver business applications (ERP, CRM, vertical SaaS) to customers, with your changes kept private.
- **Attribution required** — Retain copyright notices and "Powered by AuraBoot" branding (the upper-left main logo may be replaced).
- **Platform restriction** — You may not offer AuraBoot itself as a hosted low-code / no-code / AI platform service without a commercial license.

📖 **See the [License FAQ (中文)](LICENSE-FAQ.md) / [License FAQ (English)](LICENSE-FAQ-en.md)** for common questions about commercial use, modification, redistribution, and SaaS boundaries.

For commercial licensing (multi-tenant low-code SaaS, white-labeling, or removing branding), contact [license@auraboot.com](mailto:license@auraboot.com).

---

<p align="center">Built with care by the <a href="https://github.com/AuraBootTeam">AuraBoot Team</a></p>
