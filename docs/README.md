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
| [DSL Reference](../docs/system-reference/core/09-DSL能力边界完整参考.md) | Complete DSL capability map -- models, fields, commands, pages |
| [Command System](../docs/system-reference/core/06-Command系统.md) | 20-stage command pipeline reference |
| **Plugin Development** | |
| [Plugin Development Guide](../docs/system-reference/plugins/02-插件开发指南.md) | Build and publish plugins |
| [Plugin Manifest Schema](../plugins/schemas/plugin-manifest.schema.json) | JSON Schema for `plugin.json` |
| **Architecture** | |
| [System Reference](system-reference/) | Architecture and subsystem documentation |
| [Database Schema](system-reference/reference/01-数据库关键表Schema速查.md) | Table and column reference |
| **API Reference** | |
| [Swagger UI](http://localhost:6443/swagger-ui.html) | Interactive API docs (requires running backend) |
| **Deployment** | |
| [Docker Compose](getting-started/installation.md#docker-compose) | Production-ready Docker deployment |

## Quick Links

- [GitHub Repository](https://github.com/AuraBootTeam/auraboot)
- [Discord Community](https://discord.gg/auraboot)
- [Report an Issue](https://github.com/AuraBootTeam/auraboot/issues)
- [CRM Starter Plugin](../plugins/crm-starter/) -- Reference plugin with 6 models
