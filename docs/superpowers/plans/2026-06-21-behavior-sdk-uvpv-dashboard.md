---
type: plan-impl
status: shipped
created: 2026-06-21
slug: behavior-sdk-uvpv-dashboard
---

# Behavior SDK + UV/PV Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the narrow end-to-end vertical of SoT §12 M1: a browser SDK that auto-captures pageview/click, posts to the already-golden `/api/collect`, and a DSL `kind:detail` dashboard that visualizes UV/PV from the already-golden analytics API — proven by a real-browser golden.

**Architecture:** New `@auraboot/track` workspace package emits the server's flat camelCase `BehaviorEventInput` envelope, batches, and sends via the platform http-client with `keepalive` (carries `Authorization: Bearer`; `sendBeacon` cannot). BlockRenderer stamps `data-aura-element-id` so clicks get stable DSL-derived identity. The dashboard is a `kind:detail` workbench page whose `number-card`/`table` chart blocks consume the analytics endpoints (reshaped to the standard `{code:'0',data:{records:[...]}}` chart-api contract).

**Tech Stack:** TypeScript / React Router (web-admin), vitest (unit), Playwright (golden), Spring Boot + MyBatis (platform), DSL page JSON (import-directory-sync).

## Global Constraints

- **Language:** all source/comments/commits in English; this plan + design docs in 简体中文.
- **Host-first, zero docker** for all run/test/golden (reuse resident brokers via `dev.sh runtime` bootRun + host Vite/BFF + Playwright's own chromium + `auth.setup`).
- **Production-ready, not MVP** — no scope-trimming framing.
- **Transport = `fetch` + `keepalive` + `Authorization: Bearer`**, NEVER `sendBeacon` (auth is a Bearer header; sendBeacon cannot set it). Verified: `http-client/URLBuilder.ts:89`.
- **Dashboard page kind = `detail`** (NOT `dashboard`/`composite` — not importable per `PageSchemaValidator`). `schemaVersion=4`. Menu path `/p/c/{pageKey}`.
- **Envelope = flat camelCase** matching `BehaviorEventInput` (`eventId`/`eventName`/`eventCategory`/`clientSessionId`/`uiElementId`/`pageId`/`blockId`/`elementCode`/`props` …), NOT nested `ui_element{}`.
- **Privacy baseline is non-negotiable** (even though governance UI is deferred): never capture input/textarea value, innerHTML, full textContent, full href/query, record/content ids. Only `ui_element_id`, tag, role, allowlisted aria, sanitized route template.
- **UV semantics:** UV = distinct logged-in users this slice (no anon_id, no anonymous collect). Golden proves UV>1 with ≥2 distinct users.
- **Repo:** OSS `auraboot`, branch `feat/behavior-sdk-dashboard-m1`, worktree `/Users/ghj/work/auraboot/auraboot-behavior-sdk-dashboard`. Never commit canonical main.
- **Gates before merge:** vitest green + analytics IT green + DSL `import-directory-sync` `success:true` + real-browser golden green. CI is off; local gates are authoritative.

---

## File Structure

| Path | Responsibility |
|---|---|
| `platform/.../behavior/controller/BehaviorAnalyticsController.java` (modify) | Reshape `/overview`,`/top-events` returns to `{records:[...]}` |
| `platform/.../behavior/dto/BehaviorAnalyticsRecords.java` (create) | Small `{records:[...]}` wrapper DTO (or `Map.of("records", …)`) |
| `web-admin/app/shared/services/http-client/types.ts` (modify) | Add `keepalive?: boolean` to `FetchOptions` |
| `web-admin/app/shared/services/http-client/URLBuilder.ts` (modify) | Thread `keepalive` into `RequestInit` |
| `web-admin/packages/track/` (create) | `@auraboot/track` SDK package |
| `web-admin/packages/track/src/types.ts` | TS envelope type (mirrors `BehaviorEventInput`) |
| `web-admin/packages/track/src/envelope.ts` | `buildEvent()`, `generateEventId()`, `sanitizeRoute()` |
| `web-admin/packages/track/src/identity.ts` | `deriveUiElement()` + privacy allowlist redaction |
| `web-admin/packages/track/src/tracker.ts` | `createTracker()`: queue, flush triggers, transport, pageview/click |
| `web-admin/app/framework/meta/rendering/blocks/BlockRenderer.tsx` (modify) | Stamp `data-aura-element-id` |
| `web-admin/app/routes/AdminLayout.tsx` (modify) | Init tracker + pageview on route change |
| `web-admin/app/plugins/core-dashboard/.../behavior-analytics page JSON` (create) | UV/PV `kind:detail` dashboard + menu |
| `web-admin/tests/e2e/behavior/*.spec.ts` (create) | Real-browser golden |

---

### Task 1: Reshape analytics endpoints to the chart-api `{records:[...]}` contract

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/behavior/controller/BehaviorAnalyticsController.java`
- Create: `platform/src/main/java/com/auraboot/framework/behavior/dto/BehaviorAnalyticsRecords.java`
- Test: `platform/src/test/java/com/auraboot/framework/behavior/BehaviorAnalyticsControllerIT.java` (extend existing behavior IT if present)

**Interfaces:**
- Produces: `GET /api/analytics/behavior/overview` → JSON `{code:'0',data:{records:[{pageViews,uniqueVisitors,sessions,totalEvents}]}}`; `GET /api/analytics/behavior/top-events` → `{code:'0',data:{records:[{eventName,count}, …]}}`.
- Consumed by: `SmartNumberCard`/`SmartTableChart` api branch (`result.data.records`), Task 9.

- [ ] **Step 1: Confirm the platform success-envelope behavior (verify, don't assume)**

Run (against any running backend, or read an existing controller's live response):
```bash
curl -s "$AURA_API/api/ai/traces/stats" -H "Authorization: Bearer $JWT" | head -c 300
```
Expected: a `{"code":"0",...,"data":{...}}` envelope, confirming the platform globally wraps bare controller returns into `Result`. If so, controllers return the **`data`** payload only (`{records:[...]}`); the wrapper adds `code`/`desc`.

- [ ] **Step 2: Write the failing IT**

```java
@Test
void overview_returns_records_envelope() throws Exception {
    // seed: 2 distinct users, N page_view + click rows for the tenant (reuse obs-golden-65 seed helper)
    mockMvc.perform(get("/api/analytics/behavior/overview").headers(authHeaders(tenantId, userA)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.records").isArray())
        .andExpect(jsonPath("$.data.records[0].uniqueVisitors").value(2));
}
```

- [ ] **Step 3: Run it — expect FAIL** (`$.data.records` missing; current return is the bare overview object)

Run: `./gradlew :test --tests '*BehaviorAnalyticsControllerIT*' -i`
Expected: FAIL on `$.data.records`.

- [ ] **Step 4: Create the records DTO**

```java
package com.auraboot.framework.behavior.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

/** Standard chart-api shape so SmartNumberCard/SmartTableChart api branch can read result.data.records. */
@Data
@AllArgsConstructor
public class BehaviorAnalyticsRecords<T> {
    private List<T> records;
}
```

- [ ] **Step 5: Reshape the controller**

```java
@GetMapping("/overview")
public BehaviorAnalyticsRecords<BehaviorOverview> overview() {
    return new BehaviorAnalyticsRecords<>(
        List.of(behaviorEventMapper.overview(MetaContext.getCurrentTenantId())));
}

@GetMapping("/top-events")
public BehaviorAnalyticsRecords<BehaviorEventCount> topEvents() {
    return new BehaviorAnalyticsRecords<>(
        behaviorEventMapper.topEvents(MetaContext.getCurrentTenantId()));
}
// /daily left unchanged this slice (no trend widget; SmartBar/Line lack type:'api')
```

- [ ] **Step 6: Run the IT — expect PASS**

Run: `./gradlew :test --tests '*BehaviorAnalyticsControllerIT*' -i`
Expected: PASS. Confirm via `build/test-results/test/*.xml` (not the piped tail — `; echo` masks exit codes).

- [ ] **Step 7: Update the stale obs-golden-65 overview assertion** if it asserts the bare-object shape; point it at `$.data.records[0]`.

- [ ] **Step 8: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/behavior/ platform/src/test/java/com/auraboot/framework/behavior/
git commit -m "feat(behavior): reshape analytics endpoints to {records:[...]} chart-api contract"
```

---

### Task 2: Add `keepalive` passthrough to the platform http-client

**Files:**
- Modify: `web-admin/app/shared/services/http-client/types.ts:52` (FetchOptions)
- Modify: `web-admin/app/shared/services/http-client/URLBuilder.ts:77-83` (init build)
- Test: `web-admin/app/shared/services/http-client/__tests__/URLBuilder.test.ts`

**Interfaces:**
- Produces: `post(url, body, { keepalive: true })` sets `RequestInit.keepalive = true` while preserving `Authorization`/`credentials`. Consumed by Task 5 (SDK transport).

- [ ] **Step 1: Write the failing test** (next to existing URLBuilder tests, which already assert `init.headers.Authorization` / `init.credentials`)

```ts
it('threads keepalive into RequestInit', () => {
  const { init } = buildRequestInfo('/api/collect', { method: 'post', keepalive: true, token: 'my-token' });
  expect(init.keepalive).toBe(true);
  expect((init.headers as Record<string,string>)['Authorization']).toBe('Bearer my-token');
});
```
(Use the same `buildRequestInfo`/builder entry the existing `URLBuilder.test.ts:225` tests call.)

- [ ] **Step 2: Run — expect FAIL** (`init.keepalive` undefined)

Run: `cd web-admin && pnpm vitest run app/shared/services/http-client/__tests__/URLBuilder.test.ts`
Expected: FAIL.

- [ ] **Step 3: Add `keepalive` to FetchOptions** (`types.ts`, near `token?`/`timeout?`)

```ts
  /** When true, sets RequestInit.keepalive so the request survives page unload (used by telemetry beacons). */
  keepalive?: boolean;
```

- [ ] **Step 4: Thread it in URLBuilder** (right after the `init` object is built at `URLBuilder.ts:77`)

```ts
  if (options.keepalive) {
    init.keepalive = true;
  }
```

- [ ] **Step 5: Run — expect PASS**

Run: `cd web-admin && pnpm vitest run app/shared/services/http-client/__tests__/URLBuilder.test.ts`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add web-admin/app/shared/services/http-client/
git commit -m "feat(http-client): add keepalive passthrough to FetchOptions/RequestInit"
```

---

### Task 3: Scaffold `@auraboot/track` package + envelope builder

**Files:**
- Create: `web-admin/packages/track/package.json`, `index.ts`, `tsconfig.json`
- Create: `web-admin/packages/track/src/types.ts`, `src/envelope.ts`
- Test: `web-admin/packages/track/src/__tests__/envelope.test.ts`

**Interfaces:**
- Produces: `buildEvent(input: RawEventInput): BehaviorEventInput`; `generateEventId(): string`; `sanitizeRoute(path: string): string`. Consumed by Task 5/6.

- [ ] **Step 1: Scaffold package** (mirror `web-admin/packages/core/package.json`)

`package.json`:
```json
{ "name": "@auraboot/track", "version": "1.0.0", "private": true, "type": "module", "main": "./index.ts", "types": "./index.ts" }
```
`index.ts`: `export * from './src/tracker'; export * from './src/types';`

- [ ] **Step 2: Define the envelope type** (`src/types.ts`, flat camelCase mirroring `BehaviorEventInput`)

```ts
export interface BehaviorEventInput {
  eventId: string;
  schemaVersion: string;          // "1"
  eventName: string;              // "page_view" | "element_click"
  eventCategory: string;          // "navigation" | "ui_interaction"
  source: string;                 // "web"
  occurredAt: string;             // ISO8601
  clientSessionId: string;
  uiElementId?: string;
  appId?: string; pageId?: string; blockId?: string; elementCode?: string;
  identityQuality?: 'stable' | 'heuristic';
  props?: Record<string, unknown>;
}
export interface RawEventInput {
  eventName: string;
  eventCategory: string;
  clientSessionId: string;
  ui?: { uiElementId: string; appId?: string; pageId?: string; blockId?: string; elementCode?: string; identityQuality: 'stable' | 'heuristic' };
  props?: Record<string, unknown>;
}
```

- [ ] **Step 3: Write the failing test**

```ts
import { buildEvent, sanitizeRoute } from '../envelope';
it('builds a flat camelCase page_view envelope', () => {
  const e = buildEvent({ eventName: 'page_view', eventCategory: 'navigation', clientSessionId: 's1', props: { routeTemplate: '/p/c/x' } });
  expect(e.eventName).toBe('page_view');
  expect(e.schemaVersion).toBe('1');
  expect(e.source).toBe('web');
  expect(e.eventId).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/); // ULID
  expect(e.occurredAt).toBeTruthy();
});
it('sanitizeRoute strips ids and query', () => {
  expect(sanitizeRoute('/p/c/order_list/9182734?tab=x')).toBe('/p/c/order_list/:id');
});
```

- [ ] **Step 4: Run — expect FAIL**

Run: `cd web-admin && pnpm vitest run packages/track/src/__tests__/envelope.test.ts`
Expected: FAIL (module not found).

- [ ] **Step 5: Implement `src/envelope.ts`**

```ts
import type { BehaviorEventInput, RawEventInput } from './types';

const ULID_CHARS = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
export function generateEventId(): string {
  // Crockford base32, time(48b)+random(80b). Monotonic-enough for an event id.
  let now = Date.now();
  const time: string[] = [];
  for (let i = 9; i >= 0; i--) { time[i] = ULID_CHARS[now % 32]; now = Math.floor(now / 32); }
  let rand = '';
  for (let i = 0; i < 16; i++) rand += ULID_CHARS[Math.floor(Math.random() * 32)];
  return time.join('') + rand;
}

export function sanitizeRoute(path: string): string {
  const noQuery = path.split('?')[0];
  return noQuery.replace(/\/\d+(?=\/|$)/g, '/:id');
}

export function buildEvent(input: RawEventInput): BehaviorEventInput {
  return {
    eventId: generateEventId(),
    schemaVersion: '1',
    eventName: input.eventName,
    eventCategory: input.eventCategory,
    source: 'web',
    occurredAt: new Date().toISOString(),
    clientSessionId: input.clientSessionId,
    uiElementId: input.ui?.uiElementId,
    appId: input.ui?.appId, pageId: input.ui?.pageId, blockId: input.ui?.blockId, elementCode: input.ui?.elementCode,
    identityQuality: input.ui?.identityQuality,
    props: input.props,
  };
}
```

- [ ] **Step 6: Run — expect PASS.** `cd web-admin && pnpm vitest run packages/track/src/__tests__/envelope.test.ts`

- [ ] **Step 7: Commit**

```bash
git add web-admin/packages/track/
git commit -m "feat(track): scaffold @auraboot/track package + flat camelCase envelope builder"
```

---

### Task 4: Privacy-safe element identity extraction

**Files:**
- Create: `web-admin/packages/track/src/identity.ts`
- Test: `web-admin/packages/track/src/__tests__/identity.test.ts`

**Interfaces:**
- Produces: `deriveUiElement(el: Element): RawEventInput['ui'] | undefined` (reads nearest `[data-aura-element-id]`; only safe attrs). Consumed by Task 6.

- [ ] **Step 1: Write the failing test** (jsdom)

```ts
import { deriveUiElement } from '../identity';
it('derives stable identity from data-aura-element-id ancestor', () => {
  document.body.innerHTML = `<div data-aura-element-id="elm_1" data-aura-page-id="p1" data-aura-block-id="b1"><button id="x">Go</button></div>`;
  const ui = deriveUiElement(document.getElementById('x')!);
  expect(ui).toEqual(expect.objectContaining({ uiElementId: 'elm_1', pageId: 'p1', blockId: 'b1', identityQuality: 'stable' }));
});
it('NEVER captures input values', () => {
  document.body.innerHTML = `<input id="pw" value="secret" />`;
  const ui = deriveUiElement(document.getElementById('pw')!);
  // heuristic (no element-id ancestor) — must carry no value/innerHTML
  expect(JSON.stringify(ui ?? {})).not.toContain('secret');
});
```

- [ ] **Step 2: Run — expect FAIL.** `cd web-admin && pnpm vitest run packages/track/src/__tests__/identity.test.ts`

- [ ] **Step 3: Implement `src/identity.ts`**

```ts
import type { RawEventInput } from './types';

const SAFE_ARIA = new Set(['aria-label', 'aria-labelledby', 'role']);

export function deriveUiElement(el: Element): RawEventInput['ui'] | undefined {
  const host = el.closest('[data-aura-element-id]') as HTMLElement | null;
  if (host) {
    return {
      uiElementId: host.dataset.auraElementId!,
      appId: host.dataset.auraAppId,
      pageId: host.dataset.auraPageId,
      blockId: host.dataset.auraBlockId,
      elementCode: host.dataset.auraElementCode,
      identityQuality: 'stable',
    };
  }
  // Heuristic fallback: safe attrs only, NEVER value/innerHTML/textContent/href.
  const role = el.getAttribute('role') || el.tagName.toLowerCase();
  return { uiElementId: `heuristic:${role}`, identityQuality: 'heuristic' };
}
```

- [ ] **Step 4: Run — expect PASS.**

- [ ] **Step 5: Commit**

```bash
git add web-admin/packages/track/src/identity.ts web-admin/packages/track/src/__tests__/identity.test.ts
git commit -m "feat(track): privacy-safe element identity extraction (data-aura-element-id + allowlist)"
```

---

### Task 5: Tracker — queue, flush triggers, keepalive transport

**Files:**
- Create: `web-admin/packages/track/src/tracker.ts`
- Test: `web-admin/packages/track/src/__tests__/tracker.test.ts`

**Interfaces:**
- Consumes: `buildEvent` (T3), platform `post` with `{keepalive:true}` (T2), `deriveUiElement` (T4).
- Produces: `createTracker(opts: { post: PostFn; getSessionId: () => string; endpoint?: string; batchSize?: number }): Tracker` where `Tracker = { pageview(path): void; trackClick(el: Element): void; flush(): Promise<void>; init(): void; }`. `PostFn = (url, body, opts:{keepalive?:boolean}) => Promise<unknown>`.

- [ ] **Step 1: Write the failing test**

```ts
import { createTracker } from '../tracker';
it('batches and flushes via keepalive post', async () => {
  const sent: any[] = [];
  const post = (url: string, body: any, opts: any) => { sent.push({ url, body, opts }); return Promise.resolve({}); };
  const t = createTracker({ post, getSessionId: () => 's1', batchSize: 2 });
  t.pageview('/p/c/a'); t.pageview('/p/c/b');           // hits batchSize=2 -> auto flush
  await Promise.resolve();
  expect(sent).toHaveLength(1);
  expect(sent[0].url).toBe('/api/collect');
  expect(sent[0].opts.keepalive).toBe(true);
  expect(sent[0].body.events).toHaveLength(2);
  expect(sent[0].body.events[0].eventName).toBe('page_view');
});
```

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement `src/tracker.ts`**

```ts
import { buildEvent } from './envelope';
import { deriveUiElement } from './identity';
import type { BehaviorEventInput } from './types';

export type PostFn = (url: string, body: { events: BehaviorEventInput[] }, opts: { keepalive?: boolean }) => Promise<unknown>;
export interface Tracker { pageview(path: string): void; trackClick(el: Element): void; flush(): Promise<void>; init(): void; }

export function createTracker(opts: { post: PostFn; getSessionId: () => string; endpoint?: string; batchSize?: number; }): Tracker {
  const endpoint = opts.endpoint ?? '/api/collect';
  const batchSize = opts.batchSize ?? 10;
  let queue: BehaviorEventInput[] = [];

  const enqueue = (e: BehaviorEventInput) => { queue.push(e); if (queue.length >= batchSize) void flush(); };
  const flush = async () => {
    if (!queue.length) return;
    const events = queue.slice(0, 50);            // keepalive 64KB cap -> bound batch
    queue = queue.slice(events.length);
    try { await opts.post(endpoint, { events }, { keepalive: true }); }
    catch { /* drop-on-failure; idempotent eventId guards server-side double counting */ }
  };
  return {
    pageview: (path) => enqueue(buildEvent({ eventName: 'page_view', eventCategory: 'navigation', clientSessionId: opts.getSessionId(), props: { routeTemplate: path } })),
    trackClick: (el) => { const ui = deriveUiElement(el); enqueue(buildEvent({ eventName: 'element_click', eventCategory: 'ui_interaction', clientSessionId: opts.getSessionId(), ui, props: {} })); },
    flush,
    init: () => {
      document.addEventListener('click', (ev) => { if (ev.target instanceof Element) (api as Tracker).trackClick(ev.target); }, { capture: true });
      const onHide = () => { if (document.visibilityState === 'hidden') void flush(); };
      document.addEventListener('visibilitychange', onHide);
      window.addEventListener('pagehide', () => void flush());
    },
  };
  // note: `api` self-reference resolved by assigning the object to a const before return in implementation
}
```
(Implementation detail: assign the returned object to `const api` first, then reference `api.trackClick` inside `init`, to avoid the self-reference shown above.)

- [ ] **Step 4: Run — expect PASS.**

- [ ] **Step 5: Add a flush-on-hidden test** (dispatch `visibilitychange` with `document.visibilityState='hidden'`, assert pending events flush).

- [ ] **Step 6: Commit**

```bash
git add web-admin/packages/track/src/tracker.ts web-admin/packages/track/src/__tests__/tracker.test.ts
git commit -m "feat(track): tracker queue + flush triggers + keepalive transport"
```

---

### Task 6: BlockRenderer stamps `data-aura-element-id`

**Files:**
- Modify: `web-admin/app/framework/meta/rendering/blocks/BlockRenderer.tsx`
- Test: `web-admin/app/framework/meta/rendering/blocks/__tests__/BlockRenderer.identity.test.tsx`

**Interfaces:**
- Produces: every rendered block root carries `data-aura-element-id` (from `block.id`), `data-aura-block-id`, and `data-aura-page-id` (from page context) — read by `deriveUiElement` (T4).

- [ ] **Step 1: Read the file first** (zero-context): open `BlockRenderer.tsx`, find the block's outer wrapper element and the `block.id` / page context in scope.

- [ ] **Step 2: Write the failing test** — render a block via BlockRenderer and assert the wrapper has `data-aura-element-id` equal to `block.id`.

```tsx
it('stamps data-aura-element-id from block.id', () => {
  const { container } = renderBlock({ id: 'blk_kpi', blockType: 'chart', chartType: 'number-card' });
  expect(container.querySelector('[data-aura-element-id="blk_kpi"]')).not.toBeNull();
});
```

- [ ] **Step 3: Run — expect FAIL.**

- [ ] **Step 4: Add the attributes** to the block wrapper element:

```tsx
<div
  data-aura-element-id={block.id}
  data-aura-block-id={block.id}
  data-aura-page-id={pageKey}
  /* ...existing props... */
>
```

- [ ] **Step 5: Run — expect PASS.**

- [ ] **Step 6: Run the full unified-designer/rendering unit suite** (no-CI guard, per AGENTS): `cd web-admin && pnpm vitest run app/framework/meta/rendering` — expect no regressions.

- [ ] **Step 7: Commit**

```bash
git add web-admin/app/framework/meta/rendering/blocks/
git commit -m "feat(rendering): stamp data-aura-element-id on block roots for behavior identity"
```

---

### Task 7: Wire tracker into AdminLayout (init + pageview on route change)

**Files:**
- Modify: `web-admin/app/routes/AdminLayout.tsx:30-51` (existing route-change `useEffect`)
- Create: `web-admin/app/shared/services/trackerInstance.ts` (singleton wiring SDK to platform `post` + session id)
- Test: `web-admin/app/shared/services/__tests__/trackerInstance.test.ts`

**Interfaces:**
- Consumes: `createTracker` (T5), platform `post` (T2), session id from `session.ts`.
- Produces: `getTracker(): Tracker` singleton; AdminLayout calls `getTracker().init()` once and `getTracker().pageview(sanitizeRoute(location.pathname))` on route change.

- [ ] **Step 1: Write the failing test** for `trackerInstance` — `getTracker()` returns a singleton whose `post` calls the platform http-client `post` with `{keepalive:true}` (mock the http-client module).

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement `trackerInstance.ts`**

```ts
import { post } from '~/shared/services/http-client';
import { createTracker, type Tracker } from '@auraboot/track';
import { getClientSessionId } from '~/shared/services/session'; // create/persist in sessionStorage if absent

let instance: Tracker | null = null;
export function getTracker(): Tracker {
  if (!instance) {
    instance = createTracker({
      post: (url, body, opts) => post(url, body, { keepalive: opts.keepalive }),
      getSessionId: getClientSessionId,
    });
  }
  return instance;
}
```
(If `getClientSessionId` does not exist, add it to `session.ts`: read/generate a ULID in `sessionStorage` under `aura.client_session_id`.)

- [ ] **Step 4: Run — expect PASS.**

- [ ] **Step 5: Wire AdminLayout** — in the existing route-change `useEffect` (after the skipPaths guard), add:

```tsx
getTracker().pageview(sanitizeRoute(location.pathname));
```
and a one-time `useEffect(() => { getTracker().init(); }, [])`.

- [ ] **Step 6: Commit**

```bash
git add web-admin/app/routes/AdminLayout.tsx web-admin/app/shared/services/trackerInstance.ts web-admin/app/shared/services/__tests__/ web-admin/app/shared/services/session.ts
git commit -m "feat(track): init tracker in AdminLayout + emit pageview on route change"
```

---

### Task 8: UV/PV dashboard (kind:detail workbench page) + menu

**Files:**
- Read first: an existing importable DSL page in a core plugin (find via `grep -rln '"schemaVersion": 4' web-admin/app/plugins`) to copy the page envelope + how pages register + menus.json.
- Create: behavior-analytics dashboard page JSON under `core-dashboard` plugin resources + menu entry.
- Test: DSL validator (`import-directory-sync`).

**Interfaces:**
- Consumes: Task 1 endpoints (`/api/analytics/behavior/overview`,`/top-events`), `ChartBlockRenderer` (`blockType:"chart"`).

- [ ] **Step 1: Author the page** (`kind:detail`, `schemaVersion:4`), with page-level `dataSources`:

```jsonc
{
  "schemaVersion": 4, "kind": "detail", "pageKey": "behavior_analytics",
  "title": { "zh-CN": "行为分析", "en": "Behavior Analytics" },
  "dataSources": [
    { "id": "ds_behavior_overview", "type": "api", "url": "/api/analytics/behavior/overview" },
    { "id": "ds_top_events", "type": "api", "url": "/api/analytics/behavior/top-events" }
  ],
  "layout": { "type": "flow" },
  "blocks": [
    { "id": "kpi_pv", "blockType": "chart", "chartType": "number-card", "dataSource": "ds_behavior_overview",
      "title": { "zh-CN": "页面浏览 PV" }, "chartConfig": { "metricField": "pageViews" } },
    { "id": "kpi_uv", "blockType": "chart", "chartType": "number-card", "dataSource": "ds_behavior_overview",
      "title": { "zh-CN": "独立访客 UV" }, "chartConfig": { "metricField": "uniqueVisitors" } },
    { "id": "kpi_sessions", "blockType": "chart", "chartType": "number-card", "dataSource": "ds_behavior_overview",
      "title": { "zh-CN": "会话数" }, "chartConfig": { "metricField": "sessions" } },
    { "id": "kpi_total", "blockType": "chart", "chartType": "number-card", "dataSource": "ds_behavior_overview",
      "title": { "zh-CN": "事件总数" }, "chartConfig": { "metricField": "totalEvents" } },
    { "id": "tbl_top_events", "blockType": "chart", "chartType": "table", "dataSource": "ds_top_events",
      "title": { "zh-CN": "热门事件" } }
  ]
}
```
(Confirm exact `dataSources`/`chartConfig` keys against the read-first page + `DslRegistry` whitelist: 29 blockType / 13 dataType / kind∈3 / `schemaVersion=4`. `blockType:"chart"` is whitelisted.)

- [ ] **Step 2: Add menu entry** with path `/p/c/behavior_analytics` (standalone custom page; NOT `/p/{key}` which would 404).

- [ ] **Step 3: Run the platform validator (the real gate)** — host-first isolated stack, then:

```bash
curl -s -X POST "$AURA_API/api/plugins/import/import-directory-sync" \
  -H "Authorization: Bearer $JWT" --data-urlencode "path=<plugin dir>" | jq '.success'
```
Expected: `true`. (`page-golden-audit.mjs` 0/0 does NOT substitute for this.)

- [ ] **Step 4: Commit**

```bash
git add web-admin/app/plugins/core-dashboard/
git commit -m "feat(dashboard): UV/PV behavior-analytics kind:detail page (api-backed KPI cards + top-events table)"
```

---

### Task 9: Real-browser golden — full loop + privacy + UV>1

**Files:**
- Create: `web-admin/tests/e2e/behavior/behavior-sdk-dashboard.golden.spec.ts`
- Reuse: `auth.setup` + host Vite/BFF + `dev.sh runtime` bootRun backend.

**Interfaces:** Consumes everything above. Final acceptance gate.

- [ ] **Step 1: Preflight** (per AGENTS §2.1) — backend `/actuator/health` UP, Vite+BFF ports, BFF proxy, auth setup, two distinct seeded users (userA, userB).

- [ ] **Step 2: Write the golden** (real chromium, real DOM assertions, no skip):

```ts
test('behavior loop: 2 users browse -> events captured -> dashboard shows UV>1', async ({ browser }) => {
  for (const user of [USER_A, USER_B]) {
    const ctx = await browser.newContext({ storageState: storageFor(user) });
    const page = await ctx.newPage();
    await page.goto('/p/c/some_list'); await page.goto('/p/c/another_list');
    await page.getByRole('button').first().click();      // element_click on a data-aura-element-id host
    await page.waitForTimeout(500); await ctx.close();    // pagehide flush
  }
  // DB assertion: ab_behavior_event has page_view + element_click rows for both users, ui_element_id non-null
  const rows = await sql(`select event_name, ui_element_id, user_id from ab_behavior_event where tenant_id=$1`, [TENANT]);
  expect(rows.filter(r => r.event_name === 'page_view').length).toBeGreaterThanOrEqual(4);
  expect(rows.some(r => r.event_name === 'element_click' && r.ui_element_id)).toBe(true);
  // Privacy assertion: no captured value/innerHTML
  const props = await sql(`select props::text from ab_behavior_event where tenant_id=$1`, [TENANT]);
  expect(props.every(p => !/password|secret|value=/.test(p.props))).toBe(true);
  // Dashboard assertion
  const adminPage = await (await browser.newContext({ storageState: storageFor(USER_A) })).newPage();
  await adminPage.goto('/p/c/behavior_analytics');
  await expect(adminPage.getByText('独立访客 UV').locator('..')).toContainText('2');   // UV = 2 distinct users
  await expect(adminPage.locator('[data-aura-element-id="tbl_top_events"]')).toBeVisible();
  // 0 console errors
});
```

- [ ] **Step 3: Run host-first** (zero docker): bootRun backend (`dev.sh runtime`), host Vite/BFF with `PROXY_TARGET`/`SPRING_BOOT_URL` pointing at backend, Playwright own chromium. Expected: PASS, screenshots captured each step.

- [ ] **Step 4: Commit**

```bash
git add web-admin/tests/e2e/behavior/
git commit -m "test(behavior): real-browser golden — capture loop + privacy + UV>1 dashboard"
```

---

## Self-Review (plan vs spec)

**Spec coverage:**
- §1.2 端到端链路 → Tasks 1–9 cover SDK→collect→store→analytics→dashboard→golden. ✓
- §3.2 API reshape seam → Task 1. ✓
- §3.3 看板 DSL → Task 8. ✓
- §4.1 fetch+keepalive → Tasks 2,5,7. ✓
- §4.2 kind:detail → Task 8. ✓
- §4.3 元素身份 → Tasks 4,6. ✓
- §4.4 隐私基线 → Task 4 (test asserts no value/innerHTML) + Task 9 (DB privacy assertion). ✓
- §6 测试五层 → SDK unit (T3–5), rendering unit (T6), backend IT (T1), DSL validator (T8), golden (T9). ✓
- §7 验收 7 条 → Task 9 assertions. ✓
- §1.3 defer 清单 → no task touches Kafka/outbox/anon-collect/§5.4 governance/trend widgets. ✓

**Placeholder scan:** every code step shows real code; verify-steps (T1.1, T8.1) are explicit reads, not "TBD". The one self-reference caveat in T5.3 is called out with the resolution.

**Type consistency:** `buildEvent`/`RawEventInput`/`BehaviorEventInput`/`createTracker`/`Tracker`/`PostFn`/`deriveUiElement`/`getTracker`/`sanitizeRoute` used identically across T3–T7. Envelope keys match server `BehaviorEventInput` camelCase. `{records:[...]}` contract consistent between T1 (produces) and T8 (consumes).

**Known build-time confirmations (flagged, not hidden):** global Result-wrapper behavior (T1.1), exact `dataSources`/`chartConfig` keys vs DslRegistry (T8.1), BlockRenderer wrapper element (T6.1). Each has an explicit read/verify step; the validator + golden are the backstops.
