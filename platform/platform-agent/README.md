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

## Runtime state retention

Direct external tool attempts that remain `RUNNING` longer than
`agent.tool-execution.running-lease-ms` (default `900000`, 15 minutes) are fenced
into `COMPENSATION_REQUIRED` by the recovery sweep. Their immutable request is
retained for reconciliation through the existing compensation handlers or an
operator. An uncertain external outcome is never automatically dispatched again;
a late completion cannot overwrite that reconciliation state. Configure the
bound above the maximum expected provider execution time.

Completed, failed and cancelled conversation turns release their cumulative text
immediately. Their terminal metadata is retained for idempotent cancellation with
`agent.turn.terminal-retention-ms` (default `600000`, 10 minutes) and
`agent.turn.terminal-max-size` (default `1000`). These bounds do not evict active
turns. The scheduled cleanup also releases expired metadata when traffic is idle.
