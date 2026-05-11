# AuraBot Skill SPI

**Status**: Stable (C-2 batch shipped 2026-05-08, OSS branch `aurabot-skill-spi-c2`).
**Spec source of truth**: `docs/superpowers/specs/2026-05-08-aurabot-skill-spi-contract.md` (enterprise repo).
**Code root**: `platform/src/main/java/com/auraboot/framework/aurabot/skill/`.

## 1. Positioning

The Skill SPI is the **execution surface** that AuraBot V3 (and future ACP runs)
uses to invoke deterministic, audit-logged platform actions. A "skill" is a
typed `@Component` that exposes:

- a JSON Schema for its inputs (`paramsSchema()`),
- a `dryRun()` preview (optional),
- an `execute()` commit path,
- an optional `undo()` reversal,
- declared risk level (LOW/MEDIUM/HIGH/CRITICAL) and required permissions.

The SPI sits **below** ACP (which orchestrates conversation turns) and **beside**
the Command system (DSL-driven CRUD). Use the SPI when an LLM-driven turn needs
to call a deterministic backend action with idempotency, dry-run preview, undo,
and audit guarantees baked in.

## 2. REST endpoints

All endpoints rooted at `/api/aurabot/v2`. Auth: standard platform JWT. Tenant
context resolved via `MetaContext` (set by the JWT filter chain).

| Method | Path | Purpose |
|---|---|---|
| GET  | `/skills` | Discovery. Returns the `SkillMeta[]` visible to the caller (permission-filtered). ETag-tagged for cheap `304 Not Modified` replies. |
| POST | `/skill/dry-run` | Non-mutating preview. Mints a one-shot `previewToken` consumed by `/skill/execute` for risk ≥ MEDIUM skills. Rejected with `422 DRY_RUN_NOT_SUPPORTED` when `supportsDryRun()=false`. |
| POST | `/skill/execute` | Commit path. Idempotent via Redis claim + DB unique index. Replays return `200` with body `code=IDEMPOTENCY_REPLAY`. |
| POST | `/skill/undo` | Single-row reversal by `undoToken`. 30-minute window; later → `410 UNDO_EXPIRED`. |
| POST | `/skill/batch-undo` | Best-effort batch reversal by `batchId`, newest-first. Partial failures listed in `payload.failed`. |
| GET  | `/stream/{traceId}` | Reserved for streaming skills. Currently returns `503 STREAMING_NOT_AVAILABLE`. |

## 3. Error codes

Wire form: `body.code` is the **uppercase** identifier (`SkillErrorCode.code()`),
not a numeric HTTP status. Bind FE `switch` cases on the string.

| `code` | HTTP | Meaning |
|---|---|---|
| `SKILL_NOT_FOUND` | 404 | `skillName` not in registry. |
| `PARAMS_INVALID` | 400 | JSON-Schema validation failed. `body.context.fieldPath` carries the JSON Pointer to the offending field. |
| `CONFIRM_REQUIRED` | 422 | Risk ≥ MEDIUM and no `previewToken` supplied to `execute`. |
| `PREVIEW_TOKEN_INVALID` | 422 | Token unknown / expired / already consumed / params/skill mismatch. |
| `PERMISSION_DENIED` | 403 | Caller lacks one of `requiredPermissions()`. |
| `UNDO_EXPIRED` | 410 | Undo token unknown, past 30-minute window, or already undone. |
| `IDEMPOTENCY_REPLAY` | 200 | Returned with the prior result envelope when the same `(tenantId, skillName, idempotencyKey)` triple was already committed. |
| `STREAMING_NOT_AVAILABLE` | 503 | Streaming endpoint not yet wired. |
| `DRY_RUN_NOT_SUPPORTED` | 422 | Skill opted out of dry-run (`supportsDryRun()=false`). |
| `SKILL_INTERNAL_ERROR` | 500 | Uncaught skill exception. Stack logged at WARN. |

i18n: `aurabot.skill.error.<CODE>` keys live in
`platform/src/main/resources/i18n/aurabot-skill_<locale>.properties`. The
exception handler resolves `Accept-Language` → bundle entry → falls back to
`exception.getMessage()` when the key is missing.

## 4. End-to-end curl example (dev profile, `echo` skill)

```bash
# 1. Acquire JWT (standard platform login).
JWT=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' \
  | jq -r '.data.token')

# 2. Discover skills visible to the current user.
curl -s http://localhost:8080/api/aurabot/v2/skills \
  -H "Authorization: Bearer $JWT" | jq '.data[].name'

# 3. Dry-run echo — mints a previewToken (LOW risk, but the controller
#    mints it uniformly so the FE wire shape is stable).
DRY=$(curl -s -X POST http://localhost:8080/api/aurabot/v2/skill/dry-run \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{"skillName":"echo","params":{"text":"hello"}}')
echo "$DRY" | jq
TOKEN=$(echo "$DRY" | jq -r '.data.previewToken')

# 4. Execute echo. previewToken is informational at LOW risk; mandatory for
#    risk ≥ MEDIUM.
curl -s -X POST http://localhost:8080/api/aurabot/v2/skill/execute \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d "{\"skillName\":\"echo\",\"params\":{\"text\":\"hello\"},\"idempotencyKey\":\"demo-001\",\"previewToken\":\"$TOKEN\"}" | jq

# 5. Re-run with the same idempotencyKey → IDEMPOTENCY_REPLAY (HTTP 200).
curl -s -X POST http://localhost:8080/api/aurabot/v2/skill/execute \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d "{\"skillName\":\"echo\",\"params\":{\"text\":\"hello\"},\"idempotencyKey\":\"demo-001\"}" \
  | jq '.code, .data.skillName'
```

## 5. Registering a new skill

1. Create a class implementing `AuraBotSkill` annotated `@Component`. Restrict
   to safe profiles via `@Profile` if the skill is dev-only (e.g. `EchoSkill`
   uses `@Profile({"dev","test","integration-test"})`).
2. `name()` must match `^[a-z][a-z0-9-]*(:[a-z][a-z0-9-]*)?$`. Convention is
   `<domain>:<verb>` — e.g. `model:query`, `workflow:start`, `tenant:invite`.
   Duplicate names fail-fast on application bootstrap (`IllegalStateException`).
3. Provide a JSON Schema via `paramsSchema()`. The registry pre-compiles each
   schema; structural errors surface at startup.
4. Declare `riskLevel()`. The validator enforces a preview-token round-trip for
   `MEDIUM`+; LOW skills can skip the preview path entirely.
5. Override the optional flags only when the underlying behaviour matches:
   - `supportsDryRun()=true` — the skill implements `dryRun()` returning a
     side-effect-free preview. Controller will reject `/skill/dry-run` with
     `422 DRY_RUN_NOT_SUPPORTED` when this returns `false`.
   - `supportsUndo()=true` — implement `undo(undoToken)` and emit a token from
     `execute`. Reversal window is 30 minutes (controller-enforced).
   - `supportsStreaming()=true` — reserved; full plumbing lands in a later batch.
6. List required permission codes via `requiredPermissions()`. Empty set is
   allowed (e.g. dev-only diagnostic skills).
7. Add i18n entries:
   `aurabot.skill.<skill-name>.displayName` / `.description` in both `_en` and
   `_zh_CN` bundles. `displayName()` should return the bundle key.

## 6. Capability flag semantics

The flags below are **declarative hints** the registry surfaces to the FE via
`SkillMeta`. They do **not** alter execution semantics by themselves; the
controller and validator read them at request time and gate behaviour
accordingly.

- `supportsDryRun`
  - `false` → `/skill/dry-run` rejects with `422 DRY_RUN_NOT_SUPPORTED`. FE
    should hide the preview button.
  - `true` → `dryRun()` MUST be safe (no side effects). The validator does NOT
    require a preview token at LOW risk; tokens become mandatory only when
    `riskLevel() >= MEDIUM`. At LOW risk the controller still mints a token for
    a uniform wire shape — it is **informational only** and execute may safely
    omit it.
- `supportsUndo`
  - `false` → `undo()` defaults to `UnsupportedOperationException`, surfaced as
    `500 SKILL_INTERNAL_ERROR` if invoked.
  - `true` → `execute()` MUST emit `undoToken` in the result envelope; the
    controller persists it and enforces the 30-minute window.
- `supportsStreaming`
  - Reserved. Today the `/stream/{traceId}` endpoint returns `503` regardless;
    flag is forward-compatible metadata for the FE.

## 7. Idempotency and undo guarantees

- Every `execute` call MAY include `idempotencyKey` (free-form string, scoped
  by `(tenantId, skillName)`). The validator first claims the key in Redis
  (TTL = idempotency window); on claim failure it loads the canonical
  `SkillRun` row from Postgres and short-circuits to a replay envelope.
- DB unique index on `(tenant_id, skill_name, idempotency_key)` is the ultimate
  guarantor — Redis is fail-open.
- Undo windows: 30 minutes from `created_at`. Past the window → `410
  UNDO_EXPIRED`. `markUndone()` flips status irreversibly.

## 8. Profile / deployment notes

- `EchoSkill` is dev/test/integration-test only — never registered in `prod`.
- `ModelQuerySkill` is universally registered; requires `MODEL.READ` permission.
- Tests live in `platform/src/test/java/com/auraboot/framework/aurabot/skill/`;
  the IT stack uses real PostgreSQL + Redis on isolated docker ports
  (`auraboot-skills-c2` compose project).

## 9. Related

- SPI Contract spec: `auraboot-enterprise/docs/superpowers/specs/2026-05-08-aurabot-skill-spi-contract.md`
- C-2 batch plan: `auraboot-enterprise/docs/superpowers/plans/2026-05-08-aurabot-skill-spi-backend-batches.md`
- AuraBot V3 shell docs: `auraboot/docs/architecture/`
