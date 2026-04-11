# AuraBoot Documentation

Welcome to the AuraBoot documentation. AuraBoot is an open-source AI-native low-code platform for building business applications using declarative JSON DSL.

## Start Here

If you are new to AuraBoot, follow this path:

1. [Introduction](getting-started/introduction.md) -- Understand what AuraBoot is and what you can build
2. [Quick Start](getting-started/quick-start.md) -- Get AuraBoot running in 5 minutes with Docker
3. [Build Your First App](getting-started/first-app.md) -- Build a Task Tracker from scratch (30 min)

## Documentation Map

| Section | Description |
|---------|-------------|
| **Getting Started** | |
| [Introduction](getting-started/introduction.md) | What AuraBoot is, who it's for, and how it compares to alternatives |
| [Quick Start](getting-started/quick-start.md) | 5-minute setup with Docker Compose |
| [Installation](getting-started/installation.md) | Detailed installation: Docker, source build, environment variables |
| [First App Tutorial](getting-started/first-app.md) | Build a Task Tracker plugin from scratch in 30 minutes |
| **Core Concepts** | |
| [DSL Engine](core-concepts/dsl-engine.md) | Declarative configuration philosophy and resolution lifecycle |
| [Models & Fields](core-concepts/models-and-fields.md) | 22 field types, relations, formulas, and computed fields |
| [Commands](core-concepts/commands.md) | 20-stage command pipeline reference |
| [Pages & Layouts](core-concepts/pages-and-layouts.md) | Page kinds (list/form/detail/dashboard), block types, layout system |
| [Permissions](core-concepts/permissions.md) | RBAC, multi-tenant isolation, data-level security |
| [State Machines](core-concepts/state-machines.md) | Status flows, guards, transitions, and side effects |
| **Guides** | |
| [Page Designer](guides/page-designer.md) | Visual drag-and-drop page builder |
| [BPM Workflows](guides/bpm-workflows.md) | BPMN 2.0 process design, human tasks, SLA |
| [Automation Rules](guides/automation-rules.md) | Event-driven triggers, conditions, and actions |
| [AI Copilot](guides/ai-copilot.md) | AuraBot, ChatBI, RAG knowledge base |
| [Dashboards](guides/dashboards.md) | Charts, stat cards, KPI boards |
| [Data Import/Export](guides/data-import-export.md) | CSV/Excel import, bulk operations, export |
| [Formulas & Expressions](guides/formulas-and-expressions.md) | Computed fields, rollups, auto-number |
| [Notifications](guides/notifications.md) | Email, in-app, webhook notifications |
| [Multi-Tenancy](guides/multi-tenancy.md) | Tenant provisioning, isolation, administration |
| [CLI Reference](guides/cli-reference.md) | All `aura` CLI commands with examples |
| **Use Cases** | |
| [Use Case Index](use-cases/README.md) | 15 industry solutions with capability matrix |
| [CRM](use-cases/crm.md) | Customer relationship management |
| [Sales](use-cases/sales.md) | Sales pipeline, quoting, orders |
| [Project Management](use-cases/project-management.md) | Projects, tasks, milestones, Gantt |
| [Procurement](use-cases/procurement.md) | Purchase orders, supplier management |
| [Manufacturing](use-cases/manufacturing.md) | Production planning, BOM, MRP |
| [Warehouse](use-cases/warehouse.md) | Inventory, WMS, stock management |
| [Logistics](use-cases/logistics.md) | Shipping, tracking, delivery |
| [Finance](use-cases/finance.md) | Invoicing, AP/AR, tax compliance |
| [Quality Management](use-cases/quality-management.md) | Inspections, NCR, CAPA |
| [Compliance](use-cases/compliance.md) | Regulatory audits, risk management |
| [Asset Management](use-cases/asset-management.md) | Equipment, maintenance, lifecycle |
| [HR Leave Management](use-cases/hr-leave-management.md) | Leave requests, approvals |
| [Knowledge Base](use-cases/knowledge-base.md) | Document management, RAG search |
| [PCBA Industry Solution](use-cases/pcba-industry-solution.md) | Full PCBA manufacturing suite |
| [AI Agent Platform](use-cases/ai-agent-platform.md) | Multi-agent orchestration, AI employees |
| **Plugin Development** | |
| [Overview](plugin-development/overview.md) | Plugin architecture and lifecycle |
| [Config-Only Plugin](plugin-development/config-only-plugin.md) | Build a complete plugin with only JSON |
| [Backend Plugin](plugin-development/backend-plugin.md) | PF4J Java extensions |
| [Frontend Plugin](plugin-development/frontend-plugin.md) | Module Federation React components |
| [Full-Stack Plugin](plugin-development/full-stack-plugin.md) | End-to-end plugin walkthrough |
| [Manifest Reference](plugin-development/plugin-manifest-reference.md) | Complete plugin.json schema |
| **API Reference** | |
| [REST API](api-reference/rest-api.md) | Dynamic CRUD, filters, pagination |
| [Command API](api-reference/command-api.md) | Command execution and batch operations |
| [DataSource API](api-reference/datasource-api.md) | Named queries and data sources |
| [Webhook API](api-reference/webhook-api.md) | Event webhooks and HMAC verification |
| **Deployment** | |
| [Docker](deployment/docker.md) | Docker Compose production setup |
| [Kubernetes](deployment/kubernetes.md) | K8s Helm chart deployment |
| [Configuration](deployment/configuration.md) | Environment variables and application.yml |
| [Upgrading](deployment/upgrading.md) | Version upgrade guide |
| **Architecture** | |
| [Overview](architecture/overview.md) | System architecture and request flow |
| [Tech Stack](architecture/tech-stack.md) | Technology choices and rationale |
| [Data Model](architecture/data-model.md) | Core database schema and conventions |

## Quick Links

- [GitHub Repository](https://github.com/AuraBootTeam/auraboot)
- [Discord Community](https://discord.gg/auraboot)
- [Report an Issue](https://github.com/AuraBootTeam/auraboot/issues)
