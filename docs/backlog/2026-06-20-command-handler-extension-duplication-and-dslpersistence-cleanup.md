---
created: 2026-06-20
type: backlog
status: active
area: platform/command-pipeline, platform-plugin-api
related: docs/core-concepts/commands.md
---

# CommandHandlerExtension duplication + dslPersistence workaround

Surfaced while adding `AsyncTaskAccessor` and the `dslPersistence` opt-out
(auraboot PR #905; enterprise docs PR #594). Two latent debts worth cleaning up
so the next person doesn't trip on them.

## 1. `CommandHandlerExtension` interface exists in two diverged copies

There are two git-tracked copies of the interface:

- `platform/platform-plugin-api/src/main/java/com/auraboot/framework/plugin/extension/CommandHandlerExtension.java` — what plugins compile against.
- `platform/src/main/java/com/auraboot/framework/plugin/extension/CommandHandlerExtension.java` — compiled into the platform module.

They have **drifted**: `requiresDslPersistence(...)` (the default method that gates
the implicit field-map + field-edit permission path) exists **only** in the
`platform/src` copy, not in `platform-plugin-api`. When adding `AsyncTaskAccessor`
the key + getter had to be hand-applied to **both** copies and verified separately;
the divergence made a scripted edit fail on a missing anchor.

Risks:
- A new accessor/method added to one copy but not the other silently changes
  behavior depending on classpath order at runtime.
- The two copies can keep drifting with no drift gate.

Proposed cleanup (pick one):
- Make `platform/src` consume the published `platform-plugin-api` type instead of
  shipping its own copy (single source of truth), **or**
- If the duplication is structurally required, add a drift check (e.g. a test or
  `scripts/check-*.sh`) that diffs the two files and fails on divergence, and a
  header comment in both pointing at the other.

## 2. `dslPersistence` opt-out is buried in `handlerParams`

`handlerParams.dslPersistence: false` lets a `type:create`/`update` plugin-handled
command skip the implicit field-map + field-edit permission check (so it can accept
non-model inputs and still resolve into `schema.commands`). It had to go inside
`handlerParams` because the command import only preserves a whitelist of top-level
fields (`type`/`handler`/`inputFields`/`permissions`/`handlerParams`) into
`execution_config` — a top-level `dslPersistence` key is silently dropped.

Proposed cleanup:
- Give the command import/DTO a first-class `dslPersistence` (boolean) field so it
  is discoverable and validated, and read it at top level in
  `CommandHandlerExtension.requiresDslPersistence(...)` (keep `handlerParams`
  reading for back-compat).

## Not urgent

Both are working as shipped (PR #905 verified end-to-end). This is hygiene, not a
bug — schedule when touching the command pipeline next.
