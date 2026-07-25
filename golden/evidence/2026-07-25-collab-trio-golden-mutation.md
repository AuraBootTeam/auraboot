# Collaboration trio golden — mutation evidence

Suite: `OSS-REL-COLLAB-TRIO` (`scripts/collab-trio-golden-run.sh`)

A golden nobody has watched fail is a decoration. This records the runs where each
assertion was made to go red on purpose, and go green again on restore.

Stack: host-first isolated runtime `collab-fix` (slot 86, `auraboot_86`), zero docker,
`AGENT_LLM_STUB_MODE=true`.

## 1. Inbox unread badge — `inbox-actions.spec.ts` IB-1

The badge summed every value of `/api/inbox/unread-summary`, including the response's
own `total` key, so it rendered exactly double the real unread count.

| step | change | result |
|---|---|---|
| baseline | fix in place (`summary.total`) | **PASS** — `allTabCount=6`, `serverTotal=6` |
| mutation | reverted to `Object.values(summary).reduce((a,v)=>a+v,0)` | **FAILED** — `tab shows 12; server says total=6 (per-type sum=6)` |
| restore | fix re-applied | **PASS** |

The assertion also pins the negative case (`not.toBe(serverTotal + perTypeSum)`), and the
spec seeds unread items first — with zero unread, `total` and `2 × total` are both 0 and
the check would pass no matter what the page rendered.

## 2. Notification category filter — `NotificationQueryServiceImplTest`

`listByUser` accepted a `category` and silently discarded it; every tab returned the full
list. The mapper had no category-aware query at all.

| step | change | result |
|---|---|---|
| baseline | `.apply(hasCategory, "LOWER(category) = {0}", …)` | **PASS** — 12 tests, 0 failures |
| mutation | condition forced false (filter dropped) | **FAILED** — 2 tests red: `listFiltersByCategory`, `listCombinesCategoryAndReadState` |
| restore | condition restored | **PASS** — 12 tests, 0 failures |

Exactly the two tests that guard the filter went red — no more, no fewer.

## 3. Live-stack outcomes pinned by the same run

Verified against the real stack rather than mocks, before and after the fix:

| behaviour | before | after |
|---|---|---|
| `GET /api/notifications?category=approval` | 4 rows (unfiltered) | 1 row |
| `GET /api/notifications?isRead=true` | 4 rows (3 of them unread) | 1 row |
| `DELETE /api/notifications/batch?ids=<id>` | **404** (endpoint absent) | 200, `data=1`, row gone |
| same DELETE against another member's row | n/a | 200, `data=0`, row untouched |
| header notification bell | absent (component had zero imports) | rendered, badge + dropdown open |
| REST `@mention` → recipient Inbox | 0 items | 1 MENTION item with deep link |
| structured `mentionTargets` → agent reply | no reply | agent reply persisted |

## 4. What proving the gate green caught

Running `scripts/collab-trio-golden-run.sh` for the first time — before trusting it —
failed 2 of 26 (`IB-1`, `NC-6`); the immediate re-run passed 26/26. A gate that is red
on a cold stack and green on the next run is worse than no gate: people learn to
re-run it instead of reading it.

Cause: `oss-golden-stack.sh up` pre-warms only `/report-designer` and `/dashboard`, so
`/inbox` and `/notifications` are compiled by Vite on first navigation and the first
render can outrun the assertion timeouts. The stack script documents exactly this
fallback ("curl the route once before running"), so the runner now pre-warms both
routes between stack bring-up and the test run.

Recorded here rather than quietly fixed: the flakiness was real, and the fix is a
mitigation for cold-start compile latency, not a proof that no timing sensitivity
remains.
