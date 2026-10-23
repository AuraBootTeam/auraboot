---
type: system-reference
status: active
created: 2026-10-23
---

# Scheduled command bindings

The existing database scheduler owns cron/timezone registration, manual triggers and execution logs. `scheduledTaskCommandHandler.execute(ScheduledTask)` delegates an approved existing command through the ordinary `CommandExecutor` pipeline. It does not invoke plugin handlers directly or grant system data access.

## Deployment authority

`aura.scheduler.commands.enabled` defaults to false. Deployment-only `bindings` contain `code`, `taskPid`, `tenantId`, `actorUserPid`, `commandCode`, `modelCode` and `targetRecordPid`. At most 128 bindings are accepted; binding codes and task PIDs must be unique. No business identity, command, target or payload is selected from task JSON or from an inherited scheduler thread context.

Task creation persists the authenticated tenant from `MetaContext`; absent or nonpositive tenant context is rejected before persistence or scheduling. The request cannot supply tenant identity. Historical unscoped tasks are not silently assigned to a tenant.

Create a disabled task through the existing scheduled-task management API, then explicitly configure its exact PID in deployment settings. Task JSON is exactly `{"bindingCode":"<registered-code>"}`. The handler bean is `scheduledTaskCommandHandler`, method `execute`; automatic retries must be zero. Existing scheduler permissions still govern task management. A copied task, another tenant, extra JSON fields, unknown binding or disabled registration fails before identity/data reads. This adds no public business API and does not create tasks on application startup.

## Command execution

Each invocation clears inherited execution authority, resolves the current active user and tenant membership from IAM, reloads current roles, and checks that the command remains published for the bound model. It reads the exact target through the normal tenant/data-scope service and supplies its current integer row version, an empty payload, a fresh request ID and scheduled-task audit context to `CommandExecutor`. Normal permissions, target/version checks, handlers, transactions, invariants and command audits remain authoritative. Permission rejection or an incomplete pipeline result fails the scheduled run. The prior thread context is restored on success and failure.

Delegation is not a replacement for command-specific result/recovery semantics. An operator must investigate failed/unknown business results; the scheduler does not automatically resubmit them. Commands that need external write recovery cannot be enabled by merely configuring retries.

## Product scheduling

Products supply their own approved target and command metadata. For an account-wide synchronization command a stable successful execution may serve as its existing authority anchor; the command must still validate connection/account and the complete business scope. Cron and IANA timezone are ordinary task fields, independent of server JVM timezone. Nightly order closure must preserve shipping/receipt facts and treat incomplete or failed provider reads as failure, never inferred closure.

Implementation and per-source validation belong in the product acceptance report. Unit tests of this handler do not certify actual scheduler dispatch, IAM, database transactions or external provider reads.
