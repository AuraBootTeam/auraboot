# OSS BPM Closure Spec 1 — Frontend Plan (v2.1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement task-by-task. Steps use `- [ ]` checkboxes.
>
> **Supersedes**: `2026-04-17-oss-bpm-closure-spec1-plan-v2.md` Task 9-16 only (backend Task 1-8 remain authoritative).
>
> **Reference design**: `docs/superpowers/specs/2026-04-17-bpm-module-target-architecture-design.md`

**Goal:** Wire backend BPM capabilities (already shipped in v2 Task 1-8) into the OSS frontend — button-triggered process start + detail-page `bpm-panel` block with 4 sections (Status / Diagram / Operations / History).

**Architecture reality check (prerequisite to these tasks):**

1. **Dispatcher already exists** — `useActionHandler.handleAction()` at `web-admin/app/framework/meta/hooks/useActionHandler.ts:281-450` is the real dispatcher. It switches on `ActionDef.type` (already handles `command` / `state_transition` / `navigate` / `builtin` / `flow`) with access to `record`, `context`, `runtime`, `navigate`, `showToast`, `t`, `token`, `dataSourceManager`.
2. **Page-level renderers use the dispatcher** — `ListPageContent.tsx:783`, `FormPageContent.tsx`, `DetailPageContent.tsx`, `useDslForm.ts` all call `useActionHandler({...}).handleAction(button, record)`.
3. **Block-level renderers bypass the dispatcher** — `FormButtonsBlockRenderer.tsx:32`, `ToolbarBlockRenderer.tsx:24`, `TableBlockRenderer.tsx:253` call `runtime.executeHandler(handler, args)` directly on legacy `button.events.onClick.handler` or `button.handler` strings. They do **not** consume `button.action: ActionDef`.
4. **`ActionDef` is a discriminated union** at `web-admin/app/framework/meta/schemas/types.ts:263`, not a `type`-property interface. Extension is via new union variant.
5. **Backend endpoints ready** (committed in v2 Task 6/3/5):
   - `POST /api/bpm/process-instances` — start process from action
   - `POST /api/bpm/tasks/{id}/withdraw` — strict/loose/none policy
   - `POST /api/bpm/tasks/{id}/cc` — initiator/assignee/all policy

**Tech Stack:** React 18 / TypeScript / Vite / Vitest / Playwright / Tailwind

---

## 原 v2 Task 9-16 问题总结（为什么重写）

| 原假设 | 实际 |
|---|---|
| `ActionDef` 有 `executionMode` property | `ActionDef` 是 discriminated union，应加新 variant |
| 改 `app/shared/dsl/types.ts` | 类型在 `app/framework/meta/schemas/types.ts:263`（`shared/dsl/` 不存在） |
| 改 `app/shared/action/ActionExecutor.ts` | dispatcher 是 `hooks/useActionHandler.ts` |
| Dispatcher 里直接 `fetch('/api/bpm/process-instances')` | 应走现有 `plugins/core-bpm/services/bpmWorkbenchService.ts`（已有 `startProcess` pattern）|
| Block renderer 已消费 `button.action` | Block renderer 还在读 legacy `button.events.onClick.handler` — 需先迁移 |
| 新 block 注册到 Studio ActionScheduler | 那是 Designer 内部系统，不是 Runtime；Runtime 按 `block.blockType` 在 `DetailBlockRenderer` 分派 |

---

## Task 9a: 迁移 block-level renderer 到 useActionHandler（纯 refactor）

**Why separated from 9b**: 纯 refactor，零功能变化。单独 merge 后跑一次全量 E2E 基线，确认无回归，再做 bpm 变更。避免混合 refactor + feature 导致 revert 困难。

**Files:**
- Modify: `web-admin/app/framework/meta/rendering/blocks/FormButtonsBlockRenderer.tsx`
- Modify: `web-admin/app/framework/meta/rendering/blocks/ToolbarBlockRenderer.tsx`
- Modify: `web-admin/app/framework/meta/rendering/blocks/TableBlockRenderer.tsx`
- Create: `web-admin/app/framework/meta/rendering/blocks/__tests__/block-renderer-actions.test.tsx`

- [ ] **Step 1: 理解现有 block renderer 差异**

当前 3 个 block renderer 的 handleButtonClick 实现各不相同：

```bash
grep -n "handleButtonClick\|executeHandler\|button.handler\|button.events" \
  web-admin/app/framework/meta/rendering/blocks/FormButtonsBlockRenderer.tsx \
  web-admin/app/framework/meta/rendering/blocks/ToolbarBlockRenderer.tsx \
  web-admin/app/framework/meta/rendering/blocks/TableBlockRenderer.tsx
```

记录每个 renderer 当前行为（handler 来源、args 传递、record 注入、error handling）。

- [ ] **Step 2: 迁移 FormButtonsBlockRenderer**

当前（`FormButtonsBlockRenderer.tsx:29-37`）：

```typescript
const handleButtonClick = (button: ButtonConfig) => {
  if (!button.events?.onClick) return;
  const handler = button.events.onClick.handler;
  if (handler) {
    runtime.executeHandler(handler, button.events.onClick.args || {});
  }
};
```

替换为：

```typescript
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';

// Inside component body (need runtime.getContext() for tableName/record/etc):
const context = runtime.getContext();
const { handleAction } = useActionHandler({
  tableName: context.modelCode ?? '',
  loadData: async () => { await runtime.reload?.(); },
  runtime,
  // other ctx fields per hook signature — read useActionHandler's param interface
});

const handleButtonClick = (button: ButtonConfig) => {
  handleAction(button, context.form ?? context.data);
};
```

**关键兼容性**：原实现只读 `button.events.onClick.handler`。`useActionHandler` 先 `normalizeAction()` 把 legacy 格式（`events.onClick.handler` / `commandCode` / `navigateTo` / `apiAction`）归一化成 `ActionDef` 再分派。所以迁移后行为向后兼容。

- [ ] **Step 3: 迁移 ToolbarBlockRenderer**

当前（`ToolbarBlockRenderer.tsx:23-31`）：

```typescript
const handleButtonClick = async (button: any) => {
  if (button.handler) {
    await runtime.executeHandler(button.handler, {});
  }
};
```

替换逻辑同 Step 2。注意 toolbar button 可能**没有 `record`**（列表页工具栏），`handleAction` 的 record 参数传 `undefined`。

- [ ] **Step 4: 迁移 TableBlockRenderer**

当前（`TableBlockRenderer.tsx:253` 附近）：读 `button.handler` + `row` 并调 `executeHandler`。

替换为 `handleAction(button, row)`，row 作为 record 传入。

- [ ] **Step 5: 新增 block renderer 单元测试**

创建 `web-admin/app/framework/meta/rendering/blocks/__tests__/block-renderer-actions.test.tsx`：

测试 3 个 renderer 的按钮点击都会调到 `useActionHandler.handleAction`（用 `vi.mock` 替换 hook 返回 mock function），并验证：
- 新格式按钮（`button.action: { type: 'command', command: 'xxx' }`）被归一化并 dispatch
- Legacy 按钮（`button.events.onClick.handler` 或 `button.handler` 字符串）同样走 dispatcher（经 `normalizeAction`）
- Toolbar 按钮 record 为 undefined 时不崩

每 renderer 至少 2 测试 = 共 6 测试。

- [ ] **Step 6: 运行测试**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/web-admin
pnpm vitest run app/framework/meta/rendering/blocks/__tests__/block-renderer-actions.test.tsx \
  2>&1 | tee /tmp/pw-task9a.log | tail -40
```

Expected: 6 PASSED.

- [ ] **Step 7: E2E 冒烟基线（关键验收步骤）**

Task 9a 是 refactor，必须跑真 E2E 冒烟确认零回归：

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1
bash scripts/oss-test.sh --smoke 2>&1 | tee /tmp/pw-task9a-e2e.log
```

（`scripts/oss-test.sh` 使用 `oss-scope.json` 白名单，只跑 OSS 范围内的 E2E —— 见 memory `reference_oss_test_runner.md`。）

**验收标准**：冒烟 pass 率 ≥ 基线（和 refactor 前一致，允许 flaky retry 内）。任何新失败都必须在 commit Task 9a 前修好。

- [ ] **Step 8: Commit**

```bash
git add web-admin/app/framework/meta/rendering/blocks/FormButtonsBlockRenderer.tsx \
        web-admin/app/framework/meta/rendering/blocks/ToolbarBlockRenderer.tsx \
        web-admin/app/framework/meta/rendering/blocks/TableBlockRenderer.tsx \
        web-admin/app/framework/meta/rendering/blocks/__tests__/block-renderer-actions.test.tsx
git commit -m "refactor(web): route block-level button clicks through useActionHandler

- FormButtons/Toolbar/TableBlock renderers now call useActionHandler.handleAction
  instead of runtime.executeHandler directly
- Behavior unchanged: normalizeAction handles legacy button.events.onClick.handler
  and button.handler paths, then dispatches by ActionDef.type
- Unblocks future ActionDef variants (BPM) to be added in one place (dispatcher
  switch) rather than per-renderer"
```

---

## Task 9b: ActionDef bpm variant + dispatcher branch

**Files:**
- Modify: `web-admin/app/framework/meta/schemas/types.ts`
- Modify: `web-admin/app/framework/meta/hooks/useActionHandler.ts`
- Modify: `web-admin/app/plugins/core-bpm/services/bpmWorkbenchService.ts`
- Create: `web-admin/app/framework/meta/hooks/__tests__/useActionHandler.bpm.test.ts`

- [ ] **Step 1: 添加 BPM variant to ActionDef**

`web-admin/app/framework/meta/schemas/types.ts:263`，把 ActionDef union 扩成：

```typescript
export type ActionDef =
  | { type: 'command'; command: string }
  | { type: 'state_transition'; command: string }
  | { type: 'navigate'; to: string; command?: string }
  | { type: 'builtin'; name: string }
  | { type: 'flow'; steps: FlowStep[] }
  | { type: 'flow'; handler: string }
  | {
      type: 'bpm';
      /** BPMN process definition key (matches <process id="..."> in .bpmn file) */
      processDefinitionKey: string;
      /** Field name on the source record providing the businessKey */
      businessKeyField: string;
      /** Variable name → JSONPath mapping; only "$.field[.sub]" supported, brackets rejected */
      variables?: Record<string, string>;
    };
```

- [ ] **Step 2: 扩展 bpmWorkbenchService**

`web-admin/app/plugins/core-bpm/services/bpmWorkbenchService.ts` 现有 `startProcess` pattern。新增 action-specific helper：

```typescript
export interface StartProcessFromActionRequest {
  processDefinitionKey: string;
  businessKey: string;
  variables?: Record<string, unknown>;
}

export interface StartProcessFromActionResponse {
  processInstanceId: string;
  /** True if an existing running instance was found for this businessKey (no new start) */
  deduped?: boolean;
}

export async function startProcessFromAction(
  req: StartProcessFromActionRequest,
): Promise<StartProcessFromActionResponse> {
  const { data } = await post<ApiResponse<StartProcessFromActionResponse>>(
    '/api/bpm/process-instances',
    req,
  );
  if (!data) {
    throw new Error(`BPM start failed: empty response for ${req.processDefinitionKey}`);
  }
  return data;
}
```

**注意**：严格按 backend `BpmActionExecutor` 约定的响应格式。不做 fallback 多路解析（项目红线 `feedback_no_api_fallback.md`）。响应格式未知时先 grep backend controller 确认。

- [ ] **Step 3: 在 useActionHandler 加 bpm case**

`web-admin/app/framework/meta/hooks/useActionHandler.ts:291` switch 末尾、`flow` case 之后加：

```typescript
case 'bpm': {
  if (confirmKey) {
    const confirmed = await showConfirmDialog(confirmKey);
    if (!confirmed) return;
  }
  const { processDefinitionKey, businessKeyField, variables: varMap } = actionDef;
  const src = record || context.data || {};
  const businessKeyRaw = src[businessKeyField];
  if (businessKeyRaw === undefined || businessKeyRaw === null
      || String(businessKeyRaw).trim() === '') {
    throw new Error(
      `action.type=bpm: record missing or blank businessKeyField "${businessKeyField}"`,
    );
  }
  const resolvedVars: Record<string, unknown> = {};
  if (varMap) {
    for (const [k, expr] of Object.entries(varMap)) {
      if (typeof expr !== 'string') continue;
      if (!expr.startsWith('$.')) {
        resolvedVars[k] = expr; // literal
        continue;
      }
      if (expr.includes('[')) {
        throw new Error(`action.type=bpm: JSONPath bracket syntax not supported: "${expr}"`);
      }
      let cursor: unknown = src;
      let resolved = true;
      for (const part of expr.slice(2).split('.')) {
        if (cursor && typeof cursor === 'object' && part in cursor) {
          cursor = (cursor as Record<string, unknown>)[part];
        } else { resolved = false; break; }
      }
      if (resolved) resolvedVars[k] = cursor;
    }
  }
  const { startProcessFromAction } = await import(
    '~/plugins/core-bpm/services/bpmWorkbenchService'
  );
  const result = await startProcessFromAction({
    processDefinitionKey,
    businessKey: String(businessKeyRaw),
    variables: Object.keys(resolvedVars).length > 0 ? resolvedVars : undefined,
  });
  showToast?.({
    type: 'success',
    message: result.deduped
      ? t('bpm.action.start.deduped', '该记录已有审批流程在运行')
      : t('bpm.action.start.success', '审批流程已启动'),
  });
  if (context.loadData) await context.loadData();
  return;
}
```

**严格语义**：
- blank/missing businessKey → 抛错（不静默跳过）
- bracket JSONPath → 抛错（和 backend `BpmActionExecutor` 一致，项目红线 no silent fallback）
- 响应的 `deduped` flag 区分"新启动" vs "已有实例"，toast 文案不同
- 成功后调 `context.loadData` 刷新列表

- [ ] **Step 4: 添加国际化文案**

`web-admin/app/locales/zh-CN/bpm.json`（或对应现有 BPM i18n 文件）加：

```json
{
  "action": {
    "start": {
      "success": "审批流程已启动",
      "deduped": "该记录已有审批流程在运行"
    }
  }
}
```

英文同步添加到 `en-US/bpm.json`。

- [ ] **Step 5: 单元测试**

创建 `web-admin/app/framework/meta/hooks/__tests__/useActionHandler.bpm.test.ts`：

```typescript
import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useActionHandler } from '../useActionHandler';
import * as bpmSvc from '~/plugins/core-bpm/services/bpmWorkbenchService';

describe('useActionHandler - action.type=bpm', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('POSTs startProcessFromAction with resolved businessKey + variables', async () => {
    const spy = vi.spyOn(bpmSvc, 'startProcessFromAction').mockResolvedValue({
      processInstanceId: 'pi-1', deduped: false,
    });
    const { result } = renderHook(() => useActionHandler({ /* ctx fixture */ }));
    await act(async () => {
      await result.current.handleAction(
        {
          code: 'submit',
          action: {
            type: 'bpm',
            processDefinitionKey: 'leave_request',
            businessKeyField: 'id',
            variables: { days: '$.days' },
          },
        },
        { id: 'rec-1', days: 3 },
      );
    });
    expect(spy).toHaveBeenCalledWith({
      processDefinitionKey: 'leave_request',
      businessKey: 'rec-1',
      variables: { days: 3 },
    });
  });

  it('throws on blank businessKey', async () => {
    const { result } = renderHook(() => useActionHandler({ /* ctx */ }));
    await expect(async () => {
      await result.current.handleAction(
        { code: 'x', action: { type: 'bpm', processDefinitionKey: 'p', businessKeyField: 'id' } },
        { id: '   ' },
      );
    }).rejects.toThrow(/blank businessKeyField/);
  });

  it('throws on bracket JSONPath', async () => {
    const { result } = renderHook(() => useActionHandler({ /* ctx */ }));
    await expect(async () => {
      await result.current.handleAction(
        { code: 'x', action: {
          type: 'bpm', processDefinitionKey: 'p', businessKeyField: 'id',
          variables: { first: '$.items[0]' },
        }},
        { id: 'rec-1', items: ['a'] },
      );
    }).rejects.toThrow(/bracket/);
  });

  it('shows deduped toast when backend reports existing instance', async () => {
    const toast = vi.fn();
    vi.spyOn(bpmSvc, 'startProcessFromAction').mockResolvedValue({
      processInstanceId: 'pi-existing', deduped: true,
    });
    const { result } = renderHook(() => useActionHandler({ /* ctx with showToast: toast */ }));
    await act(async () => {
      await result.current.handleAction(
        { code: 'x', action: { type: 'bpm', processDefinitionKey: 'p', businessKeyField: 'id' } },
        { id: 'rec-1' },
      );
    });
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({
      type: 'success',
      message: expect.stringMatching(/已有审批流程/),
    }));
  });
});
```

- [ ] **Step 6: 运行 vitest**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/web-admin
pnpm vitest run app/framework/meta/hooks/__tests__/useActionHandler.bpm.test.ts \
  2>&1 | tee /tmp/pw-task9b.log | tail -30
```

Expected: 4 PASSED.

- [ ] **Step 7: Commit**

```bash
git add web-admin/app/framework/meta/schemas/types.ts \
        web-admin/app/framework/meta/hooks/useActionHandler.ts \
        web-admin/app/plugins/core-bpm/services/bpmWorkbenchService.ts \
        web-admin/app/framework/meta/hooks/__tests__/useActionHandler.bpm.test.ts \
        web-admin/app/locales/zh-CN/bpm.json \
        web-admin/app/locales/en-US/bpm.json
git commit -m "feat(web): ActionDef supports type=bpm via useActionHandler dispatcher

- Add { type: 'bpm'; processDefinitionKey; businessKeyField; variables? } variant
- useActionHandler switch case 'bpm' resolves businessKey + JSONPath variables,
  calls bpmWorkbenchService.startProcessFromAction (POST /api/bpm/process-instances)
- Reject blank businessKey and bracket JSONPath (mirror backend BpmActionExecutor)
- Show deduped vs new toast based on backend response flag"
```

---

## Task 10: bpm-panel block skeleton + bpmWorkbenchService 扩展

**Files:**
- Create: `web-admin/app/framework/meta/rendering/blocks/BpmPanelBlock.tsx`
- Modify: `web-admin/app/framework/meta/rendering/pages/DetailPageContent.tsx`
- Modify: `web-admin/app/plugins/core-bpm/services/bpmWorkbenchService.ts`

- [ ] **Step 1: 扩展 bpmWorkbenchService**

新增 3 个 API helper（详情页 4 section 全部依赖）：

```typescript
// Fetch current process instance for a given businessKey (returns null if none)
export interface BpmInstanceForRecord {
  processInstanceId: string;
  processDefinitionKey: string;
  status: 'running' | 'approved' | 'rejected' | 'withdrawn';
  currentActivityId?: string;
  currentAssignees?: string[];
  startTime: string;
  endTime?: string;
}
export async function getInstanceForRecord(
  processDefinitionKey: string,
  businessKey: string,
): Promise<BpmInstanceForRecord | null> { /* GET /api/bpm/process-instances?... */ }

// Fetch BPMN diagram + current node highlight
export async function getDiagramForInstance(
  processInstanceId: string,
): Promise<{ bpmnXml: string; currentActivityIds: string[] }> { /* GET /api/bpm/.../diagram */ }

// Fetch audit history
export interface BpmAuditEvent {
  eventId: string; operation: string; operator: string; operatorName?: string;
  timestamp: string; comment?: string; metadata?: Record<string, unknown>;
}
export async function listAuditEvents(
  processInstanceId: string,
): Promise<BpmAuditEvent[]> { /* GET /api/bpm/.../audit */ }
```

**Blocker check**：这些 endpoint 在 backend 是否存在？

```bash
grep -rn "process-instances.*GET\|/diagram\|/audit" \
  platform/src/main/java/com/auraboot/framework/bpm/controller/
```

若缺 endpoint，Task 10 实现范围扩展到 backend controller + test。若存在，只写前端。**这是 plan 的已知 unknown**（HANDOVER v2 line 236 标注），Task 10 implementer 必须先确认。

- [ ] **Step 2: 创建 BpmPanelBlock 骨架**

`web-admin/app/framework/meta/rendering/blocks/BpmPanelBlock.tsx`：

```typescript
import React, { useEffect, useState } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import {
  getInstanceForRecord,
  type BpmInstanceForRecord,
} from '~/plugins/core-bpm/services/bpmWorkbenchService';

export interface BpmPanelConfig {
  /** Required — process definition key the record's workflow belongs to */
  processDefinitionKey: string;
  /** Optional — override businessKey field (defaults to record.pid) */
  businessKeyField?: string;
  /** Which sections to render */
  sections?: Array<'status' | 'diagram' | 'operations' | 'history'>;
}

interface BpmPanelBlockProps {
  block: BlockConfig & { config: BpmPanelConfig };
  record: Record<string, unknown>;
  recordId: string;
}

export const BpmPanelBlock: React.FC<BpmPanelBlockProps> = ({ block, record, recordId }) => {
  const config = block.config;
  const businessKey = config.businessKeyField
    ? String(record[config.businessKeyField] ?? recordId)
    : recordId;
  const sections = config.sections ?? ['status', 'diagram', 'operations', 'history'];

  const [instance, setInstance] = useState<BpmInstanceForRecord | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const inst = await getInstanceForRecord(config.processDefinitionKey, businessKey);
        if (!cancelled) setInstance(inst);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [config.processDefinitionKey, businessKey]);

  if (loading) return <div data-testid="bpm-panel-loading" className="p-4">加载审批信息...</div>;
  if (error) return <div data-testid="bpm-panel-error" className="p-4 text-red-600">{error}</div>;

  return (
    <div data-testid="bpm-panel" className="bpm-panel border rounded p-4 space-y-4">
      {/* Sections filled in by Task 11-14 */}
      {sections.includes('status') && (
        <div data-testid="bpm-section-status">{/* BpmStatusSection (Task 11) */}</div>
      )}
      {sections.includes('diagram') && (
        <div data-testid="bpm-section-diagram">{/* BpmDiagramSection (Task 12) */}</div>
      )}
      {sections.includes('operations') && (
        <div data-testid="bpm-section-operations">{/* BpmOperationsSection (Task 13) */}</div>
      )}
      {sections.includes('history') && (
        <div data-testid="bpm-section-history">{/* BpmHistorySection (Task 14) */}</div>
      )}
    </div>
  );
};

export default BpmPanelBlock;
```

- [ ] **Step 3: 在 DetailBlockRenderer 加分派**

`web-admin/app/framework/meta/rendering/pages/DetailPageContent.tsx` 的 `DetailBlockRenderer`（~line 498-540 按之前探索），按 `block.blockType === 'bpm-panel'` 分派到 `BpmPanelBlock`。

**不是**在 Studio 的 BlockRegistry 注册（那是 Designer 系统）。Runtime 侧只需在这个 renderer 加 if 分支。

- [ ] **Step 4: 单元测试 skeleton**

`web-admin/app/framework/meta/rendering/blocks/__tests__/BpmPanelBlock.test.tsx`：

- 渲染 loading → 渲染空（无实例）→ 渲染有实例的各 section placeholder
- getInstanceForRecord 抛错时 render error state
- sections 缺省时渲染全部 4 个
- `businessKeyField` 指定时用 record 的那个字段

≥ 5 测试。

- [ ] **Step 5: 运行测试 + commit**

同前，commit message：

```
feat(web): add bpm-panel block skeleton for detail pages

- BpmPanelBlock renders 4-section placeholder based on config.sections
- Resolves businessKey from record[businessKeyField] or recordId
- Calls bpmWorkbenchService.getInstanceForRecord to load current state
- DetailBlockRenderer dispatches blockType='bpm-panel' to BpmPanelBlock
- Sections filled progressively by Task 11-14
```

---

## Task 11: BpmStatusSection

**Files:**
- Create: `web-admin/app/plugins/core-bpm/components/panel/BpmStatusSection.tsx`
- Modify: `web-admin/app/framework/meta/rendering/blocks/BpmPanelBlock.tsx`

- [ ] **Step 1: 组件实现**

渲染 `BpmInstanceForRecord`：
- `status` 用不同颜色 badge（running 蓝 / approved 绿 / rejected 红 / withdrawn 灰）
- `currentActivityId` + `currentAssignees` — 当前在哪个节点、待办人
- `startTime` + `endTime`（如已结束）
- **空态**：instance 为 null → 带 CTA "该记录暂无审批流程，点击启动"（CTA 走 action.type=bpm 或外部触发按钮，本 section 不重复造轮子，只显示引导文案）

- [ ] **Step 2: BpmPanelBlock 接入**

把 Task 10 的 `{/* BpmStatusSection */}` 占位替换成 `<BpmStatusSection instance={instance} t={t} />`。

- [ ] **Step 3: 测试 + commit**

3 测试：running/approved/null。commit message `feat(web): BpmStatusSection renders instance status + empty CTA`.

---

## Task 12: BpmDiagramSection

**Files:**
- Create: `web-admin/app/plugins/core-bpm/components/panel/BpmDiagramSection.tsx`

- [ ] **Step 1: 复用现有 diagram 组件**

```bash
grep -rn "bpmn-js\|BpmnViewer\|DiagramViewer" web-admin/app/plugins/core-bpm/
```

若 OSS core-bpm plugin 已有 BPMN viewer 组件（例如 workbench 用过），**复用**。否则集成 `bpmn-js/viewer`（前端依赖）。

- [ ] **Step 2: 当前节点高亮**

调 `getDiagramForInstance(processInstanceId)` 拿 XML + `currentActivityIds`，viewer 加载后对 currentActivityIds 应用高亮样式。

- [ ] **Step 3: 空态**

instance 为 null → 不渲染 diagram（或显示"无实例"占位）。

- [ ] **Step 4: 测试 + commit**

2 测试（render with instance / render empty）。

**已知风险**：bpmn-js viewer 是 DOM-heavy，vitest JSDOM 下可能 render 困难 — 若如此，测试只验证"调 viewer 构造 + 调 importXML"，不验证实际绘制。

---

## Task 13: BpmOperationsSection + WithdrawDialog + CcDialog + BpmPermissionService

**Files:**
- Create: `web-admin/app/plugins/core-bpm/components/panel/BpmOperationsSection.tsx`
- Create: `web-admin/app/plugins/core-bpm/components/panel/WithdrawDialog.tsx`
- Create: `web-admin/app/plugins/core-bpm/components/panel/CcDialog.tsx`
- Create: `web-admin/app/plugins/core-bpm/services/BpmPermissionService.ts`

- [ ] **Step 1: BpmPermissionService**

三层权限推导（spec 决策）：
1. Action permission（backend `required_permissions` 字段，从 BPMN extension 读）
2. 身份推导：发起人 → 可撤回（策略允许时）；任务 assignee → 可审批/驳回/抄送
3. IAM 覆盖：user 有 `bpm.admin` 权限 → 全开

**接口**：

```typescript
export interface BpmPermissionResult {
  canApprove: boolean;
  canReject: boolean;
  canWithdraw: boolean;
  canCc: boolean;
  reasonsBlocked?: Record<string, string>;  // 每个被禁 action 的原因（i18n key）
}

export async function resolvePermissions(
  instance: BpmInstanceForRecord,
  currentUser: { id: string; permissions: string[] },
): Promise<BpmPermissionResult>;
```

实现优先调 backend 新接口（若已存在）或前端纯计算 + 后端 spot check。

- [ ] **Step 2: BpmOperationsSection**

按 BpmPermissionResult 渲染按钮：
- 审批 → 直接调 `POST /api/bpm/tasks/{taskId}/approve`（backend 已有？需 grep 确认）
- 驳回 → 弹 reason 输入 dialog → `POST /api/bpm/tasks/{taskId}/reject`
- 撤回 → 弹 WithdrawDialog 确认 → `POST /api/bpm/tasks/{taskId}/withdraw`
- 抄送 → 弹 CcDialog 选择 receivers → `POST /api/bpm/tasks/{taskId}/cc`
- 每个按钮 `disabled={!canXxx}`，hover tooltip 显示 reasonsBlocked[action]

- [ ] **Step 3: WithdrawDialog**

简单确认对话框，展示撤回 policy（strict/loose/none）的文案 + 二次确认（spec 决策：危险操作二次确认）。

- [ ] **Step 4: CcDialog**

receivers 选择器（user picker + 搜索），comment textarea。调用 `POST /api/bpm/tasks/{taskId}/cc`。

- [ ] **Step 5: 测试**

每个组件 3-5 测试 = 共约 15 测试。

- [ ] **Step 6: 接入 BpmPanelBlock + commit**

---

## Task 14: BpmHistorySection + audit endpoint 确认

**Files:**
- Create: `web-admin/app/plugins/core-bpm/components/panel/BpmHistorySection.tsx`
- Possibly modify: backend controller to expose listAuditEvents

- [ ] **Step 1: 确认 backend endpoint**

```bash
grep -rn "listAuditEvents\|audit-events\|/audit" \
  platform/src/main/java/com/auraboot/framework/bpm/
```

Backend Task 5/6 已有 `BpmAuditService.recordProcessStart/approve/reject/withdraw/cc`（commit `666dcd3b` BpmAuditOperation enum），但**查询端 controller endpoint 不确定存在**。

若缺 → 本 task 补 `BpmAuditController.listEvents(processInstanceId)` endpoint + integration test（~100 LOC backend）。

- [ ] **Step 2: BpmHistorySection 实现**

时间线 UI，按 `timestamp` 降序展示 BpmAuditEvent：
- 图标按 operation 类型（process_start / task_approve / task_reject / withdraw / cc / ...）
- 显示 operator + operation i18n 名称 + comment + timestamp
- 空态：无事件 → "暂无审批记录"

- [ ] **Step 3: 测试 + commit**

3 测试 frontend + 若改 backend 加 2 integration 测试。

---

## Task 15: PropertySchema for action.bpm + bpm-panel (Designer 配置面板)

**Files:**
- Modify: `web-admin/app/plugins/core-designer/components/studio/registry/blocks/bpm-panel/index.ts`（若 Designer 需要支持拖放）
- Modify: action 编辑面板的 PropertySchema

**注意**：本 task 是 **Designer 侧**的配置化（让设计器能可视化配置 bpm action/block），**不影响 Runtime**。遵循项目红线 `feedback_studio_schema_driven.md`：用 PropertySchema + SchemaBlockConfigPanel，不手写 JSX 面板。

- [ ] **Step 1: action.bpm PropertySchema**

在 action 配置面板（grep `ActionConfigPanel` 或 `ActionTypeSelector`）加 `type='bpm'` 选项 + 条件显示 3 个字段：

```typescript
const actionBpmSchema: PropertySchema[] = [
  {
    key: 'processDefinitionKey',
    label: i18n.processDefinitionKey,
    type: 'select',  // 数据源：GET /api/bpm/process-definitions
    required: true,
    dependsOn: { field: 'action.type', value: 'bpm' },
  },
  {
    key: 'businessKeyField',
    label: i18n.businessKeyField,
    type: 'field-reference',  // 选当前 model 的字段
    required: true,
    dependsOn: { field: 'action.type', value: 'bpm' },
  },
  {
    key: 'variables',
    label: i18n.variables,
    type: 'keyValueEditor',  // 现有 widget 或补齐
    dependsOn: { field: 'action.type', value: 'bpm' },
  },
];
```

**若项目无 keyValueEditor widget**：本 task scope 扩展含新建。若已有（如 `FieldConfig` 的 params 编辑器），复用。

- [ ] **Step 2: bpm-panel block PropertySchema**

block-type registry 加 bpm-panel 定义（若 Designer 工作流需要）：

```typescript
{
  type: 'bpm-panel',
  label: 'BPM 审批面板',
  icon: ..., category: 'business',
  defaultColSpan: 12,
  schema: [
    { key: 'processDefinitionKey', type: 'select', required: true, ... },
    { key: 'businessKeyField', type: 'field-reference', ... },
    { key: 'sections', type: 'multiselect', options: ['status','diagram','operations','history'] },
  ],
}
```

- [ ] **Step 3: 测试**

2-3 测试验证 Designer 配置面板能正确渲染 + 写出合法 DSL（`action.type === 'bpm'` 或 `blockType === 'bpm-panel'`）。

- [ ] **Step 4: Commit**

---

## Task 16: 文档同步 + 冒烟验证

**Files:**
- Modify: `docs/standards/architecture.md`
- Modify: `docs/system-reference/subsystems/`（BPM 子系统文档）
- Modify: `docs/handover/HANDOVER.md`
- Manual: 浏览器冒烟

- [ ] **Step 1: 红线更新**

`docs/standards/architecture.md` 加 RL-BPM-1..5：

| 红线 | 内容 |
|---|---|
| RL-BPM-1 | 业务策略走 BPMN `<smart:properties>` `aura.*`，禁止新加 DB column |
| RL-BPM-2 | CC 委托 SmartEngine NotificationService，禁止再建 ccRecord 表 |
| RL-BPM-3 | 禁止 `BpmEngine` / `BpmEngineFactory` / `adapter` 抽象层复活 |
| RL-BPM-4 | ActionDef bpm variant 只能走 `useActionHandler` 的 `bpm` case，禁止直接 fetch |
| RL-BPM-5 | bpm-panel block 是 Runtime block，不是 Studio block；配置通过 PropertySchema（红线 Studio Schema-driven） |

- [ ] **Step 2: 子系统文档更新**

`docs/system-reference/subsystems/` 下找到 BPM 文档（若无，新建 `bpm-closure.md`）：
- 审批流闭环的完整调用链（前端 button → useActionHandler → bpmWorkbenchService → backend /api/bpm/process-instances → BpmActionExecutor → ProcessEngineService → SmartEngine）
- bpm-panel block 的配置方式和 4 section 职责

- [ ] **Step 3: HANDOVER 最终更新**

标记 spec 1 所有 task 完成，记录 key lessons（本 plan 重写原因：dispatcher 已存在、路径 B 比初估轻）。

- [ ] **Step 4: 浏览器手工冒烟（硬性验收）**

> 项目红线 "交付验收纪律"：每个可见变更必须经浏览器验证

- 启动 backend + frontend：`cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1 && bash scripts/reset-and-init.sh`（或 OSS equivalent）
- 登录后端，确保有一个启用 BPM 的示例模型（或参考 backend task 中的 workflow-demo plugin）
- 创建一条记录 → 点击启动审批按钮（action.type=bpm） → **toast 提示启动成功**
- 打开详情页 → **看到 bpm-panel 4 个 section 渲染**：status 有 badge，diagram 有流程图当前节点高亮，operations 有按钮（按权限启用/禁用），history 有 process_start 事件
- 作为 assignee 用户点审批 → **主流程推进一步**；history 新增 task_approve 事件
- 发起人点撤回 → **流程终止**，status 变 withdrawn
- assignee 点抄送 → CcDialog 选收件人 → **收件人在 inbox 看到 cc notification**

全部通过后才能标记 spec 1 完成。

- [ ] **Step 5: Commit**

```bash
git add docs/standards/architecture.md \
        docs/system-reference/subsystems/ \
        docs/handover/HANDOVER.md
git commit -m "docs(bpm): spec 1 closure — red lines RL-BPM-1..5, system-reference sync, smoke pass"
```

---

## Self-Review

| Spec 决策 | Task 覆盖 |
|---|---|
| D1-D12（backend 核心） | 原 plan Task 1-8（已完成） |
| 前端 action.type=bpm | Task 9b |
| bpm-panel block（4 section） | Task 10-14 |
| Designer 配置化 | Task 15 |
| 红线文档 | Task 16 |

**留待后续 spec**：
- Spec 1.5：ab_bpm_process_definition 完整瘦身、BpmAuditQueryService 聚合、jump 收紧、timeout sunset
- Spec 4：Supervision 模块

## 完成标志

- Task 9a-16 全部 commit
- `pnpm vitest run` 含新增测试全 PASSED
- `bash scripts/oss-test.sh --smoke` E2E 冒烟 pass 率 ≥ refactor 前基线
- 浏览器手工冒烟（Task 16 Step 4）完整走通 4 section + 4 操作
- `BpmEngine` / `BpmCcRecord` / `withdrawPolicy column` 全仓 grep 仍 0 匹配（backend clean 状态保持）
- HANDOVER 标记 spec 1 完成

---

## 实施顺序与依赖

```
Task 9a (renderer migration)  ←─ 纯 refactor，独立 merge 并跑 E2E 冒烟
   ↓
Task 9b (bpm variant + dispatcher case)
   ↓
Task 10 (bpm-panel skeleton + bpmApi)
   ↓
┌──────┬──────┬──────┐
│      │      │      │
Task 11 Task 12 Task 13 Task 14  ←─ 可并行
(Status)(Diag)(Ops)   (History)
   ↓
Task 15 (Designer PropertySchema)  ←─ 可任何时机，不阻塞
   ↓
Task 16 (Docs + 手工冒烟)
```

建议 Task 11-14 用 `superpowers:dispatching-parallel-agents` 并发执行（各自独立 section 文件，无共享状态），加速 2-3 倍。

## 已知风险与 mitigation

1. **Task 10 backend endpoint 缺失** — 若 `/api/bpm/process-instances?businessKey=...`（查询）、`/diagram`、audit list 任一不存在，Task 10/12/14 scope 扩展到 backend controller。实施前先 grep 确认。
2. **Task 12 bpmn-js 测试难** — JSDOM 渲染 bpmn 困难，测试只断言调用而非结果。
3. **Task 13 权限推导复杂** — 三层 merge 可能和 backend IAM/BPM 权限有 overlap，实施前先看 backend `BpmPermissionService`（若有）避免重复逻辑。
4. **Task 15 KeyValueEditor 缺失** — 现有 PropertySchema 无该 widget，可能需要新建。若新建超出 scope，退到 type=textarea 让用户输入 JSON。
5. **E2E 冒烟依赖真实流程定义** — 需要一个 demo BPMN 流程（如 workflow-demo plugin）。若 OSS 无此 plugin，Task 16 Step 4 需临时手动部署一个最简流程 BPMN。
