---
type: backlog
status: active
created: 2026-06-13
---

# Prompt-to-App / MD-3 — LLM-key-gated verification outcomes (real DeepSeek)

> Ran with a real DeepSeek key against the host-first stack (backend :6600 ← BFF :3600 ← Vite :5273, DB `aura_boot_auraqr`). Closes the two LLM-gated follow-ups; one fix shipped, one deeper bug filed below. Key was cleaned from `ab_cloud_config` after; the chat-exposed key must be rotated by owner.

## 1. Richer NL generation — VERIFIED + fixed a latent defaults bug

Earlier I reported "the weak model only emits model+fields (model-capability-bound)". **That was wrong** — a test artifact. Real cause:

`NlModelingRequest.Options` used **primitive `boolean` + `@Builder.Default = true`**. Lombok strips the field initializer from the no-args constructor Jackson uses, so a caller sending `options: {}` (or partial options) got every option **false** → the system prompt said "Pages: no, Commands: no, Menus: no" → the model correctly emitted only model+fields. `generate()` only applied the builder defaults when `options == null` (omitted entirely).

- **Real DeepSeek proof**: `options` omitted (→ builder defaults true) OR explicit-true → `deepseek-chat` generates the **full set** (fields + pages + menus + commands + bindings) and follows the `/p/<model>` menu-path prompt fix (#633). With `options:{}` it generated nothing extra.
- **Fix** (this slice): `Options` fields `boolean` → `Boolean`; an omitted option deserializes to `null` (treated as "generate" via `optEnabled()`), only explicit `false` disables; the builder still defaults true. Unit regression test + real-stack: `options:{}` now yields pages=2/menus=2/cmds=3/bindings=2; explicit `false` still yields 0.

So generation richness is **NOT** model-capability-bound; capable models produce the full operable set when options are on (the default intent).

## 2. MD-3 in-designer AI copilot — wired & functional; the aurabot↔DeepSeek tool-loop 400 is now FIXED

> **RESOLVED 2026-06-13 (PR follows)**. Captured the exact DeepSeek 400 (provider request-body + response-body logging) — **two** OpenAI-compatible tool-call defects, both fixed in `OpenAiCompatibleLlmProvider`:
> 1. **Tool name with `:`** — AuraBoot command tools are named with command codes (`sales_lead_crm:create_sales_lead`); DeepSeek/OpenAI require `^[a-zA-Z0-9_-]+$` → `400 Invalid 'tools[0].function.name': does not match pattern`. Fix: `sanitizeToolName()` on the wire (request tools + history tool_calls) + reverse-map the model's tool_call name back to the command code in `convertResponse` so dispatch is unchanged.
> 2. **Empty/typeless tool `parameters`** — `ToolDiscoveryPort` tools carry `inputSchema = {}`; serialized as `parameters:{}` → `400 schema must be 'type: object', got 'type: null'`. Fix: `normalizeToolParameters()` → `{type:object, properties:{}}` for empty/null, adds `type:object` to a typeless non-empty schema.
> Also: the provider now logs the 4xx **response body** (was hidden behind a bare "400 Bad Request").
> **Verified**: `OpenAiCompatibleLlmProviderTest` 13/13 (name sanitize, empty-schema normalize, round-trip); real-stack — MD-3 designer "Add Stat Cards" → aurabot tool-loop (10 tools) → DeepSeek **no 400** (backend log clean after "resolved 10 tools"; UI shows no "provider request failed"). This unblocks **all aurabot chat tool-use with DeepSeek**, not just the designer.
> Remaining MD-3 nuance (separate, low-pri): `AiPageGenerateDialog` routes its page-gen prompt through the general aurabot agent tool-loop, which may respond conversationally / call tools rather than emit clean page DSL for `parsePageDslResponse`. Functional copilot + working LLM now; tightening the page-gen path to a dedicated completion is a follow-up.

### (original finding, kept for history)

`AiPageGenerateDialog` (page designer toolbar → "AI 助手") is correctly implemented and wired end-to-end: real-browser golden confirmed the designer opens, the AI panel opens (quick commands Add Chart/Filters/Stat Cards/Optimize Layout + free-text), and a command sends the prompt through `auraBotApi.chatStream` → the **aurabot agent chat tool-loop**.

**Blocker (separate bug, NOT MD-3, NOT fixed here)**: the aurabot chat tool-loop call fails:
```
ChatTurnRuntime: Chat tool-loop LLM call failed: agent=aurabot, round=0,
errorType=BadRequest, message=400 Bad Request from POST https://api.deepseek.com/v1/chat/completions
(ChatToolResolver resolved 10 tools)
```
i.e. the aurabot tool-loop sends 10 function tools to DeepSeek and DeepSeek returns 400. This affects **all aurabot chat with DeepSeek** (the whole agent layer using the chat tool-loop), not just the designer. The `/api/agent/nl-modeling/generate` path (plain completion, no tools) works fine with the same key — so it's specific to the **tool-call request shape / tool_choice** the chat tool-loop sends to DeepSeek's OpenAI-compatible API.

**Follow-up to diagnose**: capture the exact request body (reactor-netty wire DEBUG) + DeepSeek's 400 reason. Likely suspects: `tool_choice` value DeepSeek rejects (resolved per-provider in `ChatTurnRuntime.resolveToolChoiceForRound`), or a tool `inputSchema` among the 10 that DeepSeek's stricter JSON-schema validation rejects. Provider serialization is `OpenAiCompatibleLlmProvider.buildBody` (standard `{type:function,function:{name,description,parameters}}`). Fix + an aurabot-chat-with-DeepSeek golden belong in a focused slice.

MD-3 itself needs no new code — once the aurabot↔DeepSeek tool-loop 400 is fixed (or a tool-capable provider like Anthropic/OpenAI is configured), the designer copilot generates + applies to canvas.
