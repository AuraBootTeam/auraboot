# Learning Loop & Shadow Mode

The Learning Loop turns every successful agent action into raw material for
new Skills. It observes recurring patterns, proposes draft Skills,
evaluates them in Shadow Mode against real traffic, and promotes the
winners after human sign-off.

This document is the subsystem reference — for the full design see
`docs/plans/**/learning-loop.md` (spec).

## Lifecycle

```
Agent action (ab_agent_action)
   │
   ▼
PatternExtractor         (nightly cron — signature dedup)
   │
   ▼  ab_agent_learning_pattern (OBSERVED)
SkillDraftGenerator       (derives contract_yaml + tool_refs)
   │   + SkillDraftNamer   (LLM, optional)
   ▼  ab_agent_skill_draft (DRAFT_PENDING_REVIEW)
Mission Control review    (POST /api/learning/drafts/{pid}/review)
   │
   ▼  REVIEWED_OK
ShadowRunScheduler        (replay derived_from_runs)
   │
   ▼  ab_agent_shadow_run  (accumulates match / fidelity stats)
PromotionEvaluator        (nightly cron)
   │  → PROMOTED_PENDING_HUMAN  (if thresholds met)
   ▼
Mission Control final approve → ACTIVE
```

## Status transitions

`ab_agent_skill_draft.status`:

| From | To | Trigger |
|------|----|---------|
| *(initial)* | `DRAFT_PENDING_REVIEW` | SkillDraftGenerator |
| `DRAFT_PENDING_REVIEW` | `REVIEWED_OK` | operator approve |
| `DRAFT_PENDING_REVIEW` | `REVIEWED_REJECTED` | operator reject |
| `REVIEWED_OK` | `SHADOW_RUNNING` | first successful ShadowExecutor run |
| `SHADOW_RUNNING` / `REVIEWED_OK` | `PROMOTED_PENDING_HUMAN` | PromotionEvaluator thresholds met |
| `PROMOTED_PENDING_HUMAN` | `ACTIVE` | operator final approve |

## Shadow Mode

Shadow Mode replays a draft's tool_refs against real original runs
without mutating production state. Three substrate-level support
levels live in `ab_agent_dry_run_support`:

| Level | Meaning | Current seeds |
|-------|---------|---------------|
| `FULL` | Invoke as-is — side-effect free | `nq_*`, `dsl.query` |
| `SIMULATED` | Validate + before-snapshot, skip commit | `cmd_*`, `dsl.command` (since PR-40) |
| `NONE` | Unshadowable; skip directly to reinforced human gate | `code.*`, `api_*` |

Tenants override platform defaults with higher-priority rows
(`tenant_id ≠ -1`).

### How SIMULATED works for `dsl.command`

1. `DslCommandShadowInvoker` calls `CommandExecutor.execute(code, request)` with `request.dryRun = true`.
2. Full CommandPipeline runs: Load → SchemaValidate → Idempotency → Entitlement → SOD → StateCheck → FieldMap → Handler → PostExecution → Completion.
3. `HandlerPhase` suppresses the declarative BPM trigger under `dryRun`
   (BPM state lives outside the command's transaction envelope).
4. `CommandExecutorImpl` calls
   `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`
   at the end. The wrapping `@Transactional` commit becomes a rollback;
   all DB writes revert.
5. Result object is still populated from in-memory maps, so the shadow
   execution observes the full phase output.

### Invoker extension

`ShadowToolInvoker` is a Spring SPI. Substrates register beans that
`supports(toolRef)` a pattern and `invokeShadow(tenantId, toolRef, args)`
to produce a result payload. Built-ins:

- `NamedQueryShadowInvoker` — `nq_*`, `dsl.query` (FULL)
- `DslCommandShadowInvoker` — `cmd_*`, `dsl.command` (SIMULATED)

Missing invoker → `ShadowExecutor` records `shadow_status=skipped`
rather than failing, so promotion logic can distinguish "tried" from
"unshadowable".

## Promotion thresholds

Defaults (override via `acp.learning.promotion.*`):

| Key | Default | Meaning |
|-----|---------|---------|
| `min-shadow-runs` | 5 | Required accumulated runs |
| `min-output-match-rate` | 0.90 | shadow hash == original hash |
| `min-fidelity-match-rate` | 0.90 | shadow fidelity ≥ original |

`PromotionEvaluator.evaluate(pid)` always writes `shadow_metrics` JSON
onto the draft row, even when thresholds fail — the Mission Control UI
surfaces the stats inline so operators can tune or retry.

## Schedulers

Both off by default; flip per environment:

| Property | Job | Default cron |
|----------|-----|--------------|
| `acp.learning.shadow.scheduler.enabled` | ShadowRunScheduler | every 10 min |
| `acp.learning.promotion.scheduler.enabled` | PromotionEvaluationRunner | every 15 min |

## Mission Control pages

| Route | Purpose |
|-------|---------|
| `/aurabot/learning-drafts` | Draft review + evaluate-promotion + shadow run inspector |
| `/aurabot/interrupts` | Tenant-wide interrupt classifier audit |
| `/aurabot/mission-control` | Dashboard with KPIs, QuickLinks to the above |

## REST endpoints

Base path: `/api/learning` unless noted. All are tenant-scoped via
`MetaContext.getCurrentTenantId()`.

| Method | Path | Purpose |
|--------|------|---------|
| GET  | `/drafts` | List by status (optional `status` filter) |
| GET  | `/drafts/stats` | Tenant-wide counts by status |
| GET  | `/drafts/{pid}` | Draft detail + source pattern snapshot |
| GET  | `/drafts/{pid}/shadow-runs` | Shadow run history with deltas |
| POST | `/drafts/{pid}/review` | approve / reject |
| POST | `/drafts/{pid}/auto-rename` | trigger LLM namer |
| POST | `/drafts/{pid}/evaluate-promotion` | manual promotion evaluation |
| GET  | `/api/aurabot/sessions/interrupts` | Tenant-wide interrupt audit |
| GET  | `/api/aurabot/sessions/{sid}/interrupt-log` | Per-session audit |
| POST | `/api/aurabot/sessions/{sid}/interrupt` | Classify + dispatch |

## Related tables

- `ab_agent_learning_pattern` — pattern signatures, invocation counts
- `ab_agent_skill_draft` — draft lifecycle, contract_yaml, shadow_metrics
- `ab_agent_shadow_run` — per-replay comparison rows
- `ab_agent_dry_run_support` — per tool_ref dry-run capability registry
- `ab_agent_interrupt_log` — classifier output audit
- `ab_agent_skill_pack` / `ab_agent_skill_pack_binding` — SkillPack activation filter (ACP §3.3 Tier 1)

## Related PRs (feat/acp-memory-scope)

PR-25..29 set up the HITL + classifier skeleton; PR-32..44 close the
automation loop and ship the operator-facing Mission Control surface.

### Plugin handler dry-run contract

Shadow Mode runs DSL write drafts via `CommandExecutor.execute` with
`CommandExecuteRequest.dryRun=true`. `CommandExecutorImpl` marks the outer
transaction `setRollbackOnly()` **at pipeline entry** (PR-56 N13) so
JdbcTemplate / JPA writes issued inside the pipeline are reverted even if
an intermediate phase attempts an intra-transactional commit. Side effects
that escape the JDBC connection — outbound HTTP, emails, message-queue
publishes, S3 uploads, Redis writes, external-DB writes — are NOT covered
by this rollback.

To close that gap, the HANDLER phase **skips handlers that do not opt in**
to dry-run semantics (PR-56 C3), replacing the earlier honour-system
warning:

- Spring-bean `CommandHandler`: the handler class MUST be annotated with
  `@com.auraboot.framework.meta.service.DryRunSafe` for `HandlerPhase` to
  invoke it under dry-run. Otherwise the phase logs `INFO` `"Dry-run:
  skipping handler X (class not marked @DryRunSafe)"` and continues
  gracefully. When invoked, the handler still receives the flag via
  `CommandHandlerContext.isDryRun()` so it can branch internally.
- Plugin `CommandHandlerExtension`: override the SPI method
  `default boolean supportsDryRun() { return false; }` to return `true`
  when the handler is safe to execute under dry-run. Otherwise
  `HandlerPhase` skips it with an `INFO` log. When invoked, the flag is
  exposed via `CommandHandlerContext.dryRun()`.

A handler qualifies for the marker / `supportsDryRun()=true` when it
either has no side effects outside the JDBC connection managed by the
enclosing transaction, or inspects the flag internally and short-circuits
every external call.

`CompletionPhase` additionally skips (PR-56 C4) under dry-run:

- `DomainEventPublisher.publishCommandCompleted` synchronous publication
  (prevents any non-transactional `@EventListener` from firing).
- `afterCommit` registration for `api_call` / `webhook` rules.
- `afterCommit` registration for `effectExecutor.saveAuditLog` — no
  synthetic `ab_command_audit_log` rows from dry-run runs.

And `CommandExecutorImpl`'s failure-path `saveAuditLog` call is skipped
under dry-run, so a deliberately thrown error in shadow replay does not
produce a phantom audit row.

If a plugin's command handler cannot honour dry-run safely, either leave
`supportsDryRun()` at its default `false` (HandlerPhase will skip it) or
register its command code as `NONE` in `ab_agent_dry_run_support` for the
affected tenants; `DryRunSupportRegistry` will then classify the tool_ref
as ineligible and skip shadow replay entirely.

#### Phase-level audit (PR-62 R2-N2)

Beyond the HANDLER / DOMAIN_EVENT / afterCommit gates above, PR-62 audited
every remaining entry call in `CompletionPhase` and `PostExecutionPhase`:

- `CompletionPhase.effectExecutor.executeEffectPhase` — writes only to
  `ab_outbox` and the event store through MyBatis mappers that inherit the
  caller's transaction. **Kept active** under dry-run to exercise the
  rollback envelope (the whole point of shadow replay). An `INFO` log
  `"Dry-run: effect phase ran under rollback envelope"` documents the fact.
  If any future refactor introduces a write that escapes the outer
  transaction (`REQUIRES_NEW`, `@Async`, Kafka / WebSocket / HTTP
  emission), the call MUST be wrapped in `if (!dryRun)` — the comment at
  the call site flags this requirement.
- `PostExecutionPhase` — gated at the phase entry under `if (dryRun)
  return;`. Covers side-effect rules, roll-up recalculation, governance
  snapshot, and postActions (including BPM `start_process` which triggers
  SmartEngine task listeners that are easy to refactor into non-JDBC
  emissions). The gate is defensive rather than currently required.
