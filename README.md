<!-- TODO: Add banner image -->

<p align="center">
  <img src="docs/assets/logo.png" alt="AuraBoot Logo" width="120" />
  <h1 align="center">AuraBoot</h1>
  <p align="center"><strong>Open-source AI-native low-code business platform</strong></p>
</p>

<p align="center">
  <a href="LICENSE.txt"><img src="https://img.shields.io/badge/License-AuraBoot_v1.0-blue.svg" alt="License"></a>
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

<!-- TODO: Add screenshot — main dashboard -->
<!-- TODO: Add screenshot — page designer -->
<!-- TODO: Add screenshot — command pipeline -->
<!-- TODO: Add screenshot — AI copilot -->

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
PF4J-based plugin architecture with 27+ modules. Plugins are declarative JSON packages that add models, fields, commands, pages, and menus. Install from a marketplace or build your own with the CLI.

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
| **Password** | `ChangeMeOnFirstLogin!` |

### Manual Setup

**Prerequisites:** Java 21+, Node.js 20+, PostgreSQL 15+, Redis 7+

```bash
# 1. Clone the repository
git clone https://github.com/AuraBootTeam/auraboot.git
cd auraboot

# 2. Initialize the database
./scripts/reset-and-init.sh

# 3. Start the backend (Spring Boot, port 6443)
cd platform
./gradlew bootRun

# 4. In a new terminal — start the frontend (Vite + BFF, port 5173)
cd web-admin
npm install
npm run dev:full
```

Open [http://localhost:5173](http://localhost:5173) in your browser.

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
├── plugins/                  # Plugin packages (27+ modules)
│   ├── crm/                  #   CRM plugin
│   ├── sales/                #   Sales management
│   ├── procurement/          #   Procurement
│   └── ...
├── docs/                     # Documentation
│   └── system-reference/     #   Architecture and subsystem docs
├── scripts/                  # Build, seed, and CI scripts
├── docker/                   # Docker configuration
└── docker-compose.yml        # One-command infrastructure
```

## Documentation

- [Architecture Guide](docs/system-reference/) — System design and subsystem documentation
- [Plugin Development Guide](docs/system-reference/plugins/02-插件开发指南.md) — Build and publish plugins
- [DSL Reference](docs/system-reference/core/09-DSL能力边界完整参考.md) — Complete DSL capability map
- [Command System](docs/system-reference/core/06-Command系统.md) — 20-stage pipeline reference
- [Database Schema](docs/system-reference/reference/01-数据库关键表Schema速查.md) — Table and column reference

<!-- TODO: Replace with https://docs.auraboot.com when documentation site is live -->

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

AuraBoot is released under the [AuraBoot License v1.0](LICENSE.txt), a source-available license based on Apache 2.0 with additional terms:

- **Free for internal use** — Use, modify, and deploy for your own business applications
- **Attribution required** — Retain copyright notices and "Powered by AuraBoot" branding
- **Platform restriction** — You may not offer AuraBoot as a hosted low-code/no-code platform service without a commercial license

For commercial licensing (SaaS hosting, white-labeling, or removing branding), contact [license@auraboot.com](mailto:license@auraboot.com).

---

<p align="center">Built with care by the <a href="https://github.com/AuraBootTeam">AuraBoot Team</a></p>
