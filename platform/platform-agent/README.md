# Platform Agent

The OSS AI implementation library contains agent execution, conversation turns,
memory, approvals, RAG, intent, AI search, and adapters for core consumer ports.
The full application selects this library at runtime. Its implementation classes,
resources, and outbound MCP SDK dependencies belong here rather than in core.

Core owns the consumer interfaces and neutral DTOs. This project depends on core
and the plugin API; neither library depends on the application. Existing package
names and protocol behavior are retained.

Hermetic library tests use `:platform-agent:test`. Full Spring compositions and
cross-module integration tests live in `platform-app`; shared test helpers use
explicit test-fixtures variants and are excluded from production artifacts.
