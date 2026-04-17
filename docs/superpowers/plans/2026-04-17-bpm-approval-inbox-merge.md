# BPM Approval Inbox Merge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除 `/bpm/approval-inbox` 入口，将 BPM 审批任务统一到 `/bpm/task-center`，消除双入口重复。

**Architecture:** 纯删除路线——移除 ApprovalInbox 组件 / 页面 / 路由 / 菜单 / 权限条目 / 独立 E2E，TaskCenter 保持现状。开发阶段允许破坏性变更，不保留 301 或兼容 shim。

**Tech Stack:** React Router 7、TypeScript、PF4J 插件 JSON、Playwright E2E。

**Scope 调整声明（对 spec）：** spec §2 方案 A 提到"已办 tab 增加 approved/rejected 过滤 Select"——经实现前确认，`TaskInstance`（`bpmWorkbenchService.ts:10`）不含 `outcome`/`approvalResult` 字段，workbench 后端 API 也未返回该语义。为遵守 YAGNI 与不扩 scope，**本 plan 不实现该 Select**；若未来确需，独立议题为 workbench API 增加 outcome 字段。

---

## File Structure

**删除：**
- `auraboot/web-admin/app/plugins/core-bpm/pages/ApprovalInbox.tsx`
- `auraboot/web-admin/app/plugins/core-bpm/components/ApprovalInbox.tsx`
- `auraboot/web-admin/tests/e2e/approval/approval-complete-flow.spec.ts`
- `auraboot/web-admin/tests/e2e/approval/approval-workflow.spec.ts`

**保留但检查：**
- `auraboot/web-admin/tests/e2e/approval/inline-approval-panel.spec.ts`（测 drawer 内联审批面板，与 approval-inbox 无关，保留）
- `auraboot/web-admin/app/plugins/core-bpm/components/ApprovalBadge.tsx`（可能被别处引用，删前 grep）
- `auraboot/web-admin/app/plugins/core-bpm/services/approvalService.ts`（BpmTaskDrawer 可能依赖，不在本 plan 删除）

**修改：**
- `auraboot/web-admin/app/plugins/core-bpm/resources.ts` — 删除 `bpm.approval-inbox` 条目（第 20-28 行）
- `auraboot/plugins/core-bpm/config/menus.json` — 删除 `bpm_approval_inbox` 条目（第 49-62 行）
- `auraboot/plugins/core-bpm/config/permissions.json` — 删除 `bpm_approval_inbox` 条目（第 22-31 行）

**文档同步（扫到再改）：**
- `auraboot/docs/guides/bpm-workflows.md`
- `auraboot/web-admin/app/plugins/core-bpm/README.md`
- `auraboot-enterprise/docs/system-reference/subsystems/12-审批工作流系统.md`
- `auraboot-enterprise/docs/system-reference/subsystems/05-BPM工作流引擎.md`

---

## Task 1: 建工作分支

**Files:** 无

- [ ] **Step 1: 从 auraboot OSS 仓库建分支**

```bash
cd /Users/ghj/work/auraboot/auraboot
git checkout -b feat/merge-approval-inbox-to-task-center
git status
```

Expected: `On branch feat/merge-approval-inbox-to-task-center`, clean working tree.

- [ ] **Step 2: 确认 spec 已 commit**

```bash
ls docs/superpowers/specs/2026-04-17-bpm-approval-inbox-merge-design.md
ls docs/superpowers/plans/2026-04-17-bpm-approval-inbox-merge.md
git add docs/superpowers/specs/2026-04-17-bpm-approval-inbox-merge-design.md docs/superpowers/plans/2026-04-17-bpm-approval-inbox-merge.md
git commit -m "docs(bpm): spec + plan for approval-inbox merge into task-center"
```

Expected: 2 files committed.

---

## Task 2: 调查删除影响面（grep baseline）

**Files:** 无（只读）

- [ ] **Step 1: 在 auraboot OSS 全仓搜引用**

Run:
```bash
cd /Users/ghj/work/auraboot/auraboot
grep -rn "approval-inbox\|ApprovalInbox\|bpm_approval_inbox\|bpm\.approval-inbox" \
  --include="*.ts" --include="*.tsx" --include="*.json" --include="*.java" --include="*.md" \
  . 2>/dev/null | tee /tmp/approval-inbox-refs.txt
wc -l /tmp/approval-inbox-refs.txt
```

Expected：输出一个可控的引用清单。记录文件数——每条引用都要在后续 Task 中处理或确认为历史文档。

- [ ] **Step 2: 确认 ApprovalBadge 是否还有消费方**

Run:
```bash
grep -rn "ApprovalBadge" /Users/ghj/work/auraboot/auraboot --include="*.ts" --include="*.tsx"
```

Expected：若仅 `components/ApprovalBadge.tsx` 自引用 + ApprovalInbox 引用 → 可连带删除；若被其它页面/组件使用 → 保留。**记录结论**供 Task 5 使用。

- [ ] **Step 3: 确认 approvalService.getMyPendingTasks / getMyHistory 是否还有其它消费方**

Run:
```bash
grep -rn "getMyPendingTasks\|getMyHistory" /Users/ghj/work/auraboot/auraboot --include="*.ts" --include="*.tsx"
```

Expected：列出所有调用点。若仅 `ApprovalInbox.tsx` 使用 → Task 6 可删掉这两个导出；否则保留。**记录结论**。

---

## Task 3: 删除前端 pages/ApprovalInbox.tsx 与 components/ApprovalInbox.tsx

**Files:**
- Delete: `auraboot/web-admin/app/plugins/core-bpm/pages/ApprovalInbox.tsx`
- Delete: `auraboot/web-admin/app/plugins/core-bpm/components/ApprovalInbox.tsx`

- [ ] **Step 1: 删除两个文件**

```bash
cd /Users/ghj/work/auraboot/auraboot
git rm web-admin/app/plugins/core-bpm/pages/ApprovalInbox.tsx
git rm web-admin/app/plugins/core-bpm/components/ApprovalInbox.tsx
```

Expected: `rm ... (2 files deleted)`.

- [ ] **Step 2: 前端类型检查**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
npx tsc --noEmit 2>&1 | tee /tmp/tsc-after-delete.log
```

Expected: 应该出现对 `ApprovalInbox` 的引用错误（来自 `resources.ts` 等），这是 Task 4 要修的。记录错误行号。

- [ ] **Step 3: 不 commit，继续下一 Task**

说明：组件删除与 route 配置删除是同一逻辑单元，合并提交。

---

## Task 4: 从 resources.ts 中删除 approval-inbox 路由

**Files:**
- Modify: `auraboot/web-admin/app/plugins/core-bpm/resources.ts`（删除第 20-28 行整个对象）

- [ ] **Step 1: 编辑 resources.ts**

删除如下整段（含前后逗号处理）：
```ts
  {
    key: 'bpm.approval-inbox',
    path: '/bpm/approval-inbox',
    title: { en: 'Approval Inbox', zh: '审批中心' },
    icon: 'check-circle',
    menu: { order: 20, group: 'bpm' },
    permission: 'bpm.task.act',
    file: './plugins/core-bpm/pages/ApprovalInbox.tsx',
  },
```

结果文件应只剩 4 个 RESOURCES 条目：`task-center` / `process-status` / `sla-monitor`（外加尚未列出的其它）。

- [ ] **Step 2: 再跑 tsc**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
npx tsc --noEmit 2>&1 | tee /tmp/tsc-after-resource.log
grep -c "error TS" /tmp/tsc-after-resource.log || echo "0 errors"
```

Expected: 相比 Task 3 Step 2 的基线，不应有**新增** `ApprovalInbox` 相关错误。若仍有，grep 定位并在本 Task 内继续修。

- [ ] **Step 3: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot
git add web-admin/app/plugins/core-bpm/resources.ts
git commit -m "refactor(bpm): remove approval-inbox route and page components"
```

---

## Task 5: 删除插件 menus.json / permissions.json 中的 approval_inbox 条目

**Files:**
- Modify: `auraboot/plugins/core-bpm/config/menus.json`
- Modify: `auraboot/plugins/core-bpm/config/permissions.json`

- [ ] **Step 1: 从 menus.json 删除 `bpm_approval_inbox` 条目**

删除如下整段（第 49-62 行）：
```json
  {
    "code": "bpm_approval_inbox",
    "parentCode": "bpm_management",
    "name": "审批任务",
    "name:zh-CN": "审批任务",
    "name:en": "My Approvals",
    "path": "/bpm/approval-inbox",
    "component": null,
    "icon": "audit",
    "type": 1,
    "permissionCode": "bpm_approval_inbox",
    "orderNo": 23,
    "visible": true
  },
```

注意处理前后逗号，保证 JSON 合法。

- [ ] **Step 2: 从 permissions.json 删除 `bpm_approval_inbox` 条目**

删除如下整段（第 22-31 行）：
```json
  {
    "code": "bpm_approval_inbox",
    "name": "审批任务",
    "description": "查看和处理审批任务",
    "type": "menu",
    "module": "bpm",
    "resource": "/bpm/approval-inbox",
    "action": "view",
    "resourceType": "menu"
  },
```

- [ ] **Step 3: JSON 合法性校验**

```bash
cd /Users/ghj/work/auraboot/auraboot
python3 -c "import json; json.load(open('plugins/core-bpm/config/menus.json'))" && echo "menus OK"
python3 -c "import json; json.load(open('plugins/core-bpm/config/permissions.json'))" && echo "permissions OK"
```

Expected: 两行 `OK`。

- [ ] **Step 4: 再 grep 验证残留**

```bash
grep -n "approval_inbox\|approval-inbox" plugins/core-bpm/config/*.json
```

Expected: 无输出。

- [ ] **Step 5: Commit**

```bash
git add plugins/core-bpm/config/menus.json plugins/core-bpm/config/permissions.json
git commit -m "refactor(bpm): remove bpm_approval_inbox menu and permission entries"
```

---

## Task 6: 删除 E2E approval-inbox 专属用例

**Files:**
- Delete: `auraboot/web-admin/tests/e2e/approval/approval-complete-flow.spec.ts`
- Delete: `auraboot/web-admin/tests/e2e/approval/approval-workflow.spec.ts`
- Keep: `auraboot/web-admin/tests/e2e/approval/inline-approval-panel.spec.ts`

- [ ] **Step 1: 先确认两份 spec 是否与 approval-inbox 路由强耦合**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
grep -n "approval-inbox\|/bpm/approval" tests/e2e/approval/approval-complete-flow.spec.ts tests/e2e/approval/approval-workflow.spec.ts
```

Expected: 两份 spec 都直接命中 `/bpm/approval-inbox` 或 `审批任务` 菜单。若命中，删除；若实际在跑 `/bpm/task-center`，仅改导航目标即可，不删除——在这一步做判断，**记录结论**。

- [ ] **Step 2: 根据 Step 1 结论执行删除或改写**

若删除：
```bash
git rm tests/e2e/approval/approval-complete-flow.spec.ts
git rm tests/e2e/approval/approval-workflow.spec.ts
```

若改写：将菜单点击从 `审批任务` 改为 `任务中心`，URL 断言从 `/bpm/approval-inbox` 改为 `/bpm/task-center`。**只在 Step 1 明确结论后执行。**

- [ ] **Step 3: 确认 inline-approval-panel.spec.ts 不受影响**

```bash
grep -n "approval-inbox\|ApprovalInbox" tests/e2e/approval/inline-approval-panel.spec.ts
```

Expected: 无输出。若有，改写同 Step 2 规则。

- [ ] **Step 4: Commit**

```bash
git add -A tests/e2e/approval/
git commit -m "test(bpm): remove approval-inbox-bound e2e specs, keep inline-approval-panel"
```

---

## Task 7: 清理文档引用

**Files:**
- Modify: `auraboot/docs/guides/bpm-workflows.md`
- Modify: `auraboot/web-admin/app/plugins/core-bpm/README.md`（若存在引用）
- Modify: `auraboot-enterprise/docs/system-reference/subsystems/12-审批工作流系统.md`
- Modify: `auraboot-enterprise/docs/system-reference/subsystems/05-BPM工作流引擎.md`

- [ ] **Step 1: grep 文档内的 approval-inbox 引用**

```bash
grep -rn "approval-inbox\|审批收件箱\|ApprovalInbox" \
  /Users/ghj/work/auraboot/auraboot/docs \
  /Users/ghj/work/auraboot/auraboot/web-admin/app/plugins/core-bpm/README.md \
  /Users/ghj/work/auraboot/auraboot-enterprise/docs/system-reference \
  2>/dev/null | grep -v "docs/plans/2026" | grep -v "docs/superpowers/specs"
```

（过滤掉历史 plan / 当前 spec；那些保留不动。）

- [ ] **Step 2: 逐条修改**

对每处引用：
- 如果描述的是"双入口 / 审批中心独立页"，改为"统一入口 /bpm/task-center"。
- 如果是截图路径 / 旧 UX 叙述，就地删除整段或注明"（已合并到任务中心，2026-04-17）"。

- [ ] **Step 3: Commit（分仓）**

```bash
cd /Users/ghj/work/auraboot/auraboot
git add -A docs/ web-admin/app/plugins/core-bpm/README.md
git commit -m "docs(bpm): reflect approval-inbox merge into task-center"

cd /Users/ghj/work/auraboot/auraboot-enterprise
git add -A docs/system-reference/subsystems/12-审批工作流系统.md docs/system-reference/subsystems/05-BPM工作流引擎.md
git status  # 确认仅预期文件
git commit -m "docs(bpm): reflect approval-inbox merge into task-center"
```

---

## Task 8: （可选）清理 ApprovalBadge 与 approvalService 孤儿

**Files:**
- Conditionally delete: `auraboot/web-admin/app/plugins/core-bpm/components/ApprovalBadge.tsx`
- Conditionally prune: `auraboot/web-admin/app/plugins/core-bpm/services/approvalService.ts`

- [ ] **Step 1: 按 Task 2 结论判断**

- 若 Task 2 Step 2 结论为"ApprovalBadge 无其它消费方" → `git rm` 该文件。
- 若 Task 2 Step 3 结论为"getMyPendingTasks / getMyHistory 无其它消费方" → 从 `approvalService.ts` 中删除这两个导出函数（保留文件，其它函数 BpmTaskDrawer 可能仍用）。
- 若任何一条有消费方 → **跳过本 Task，不做清理**。

- [ ] **Step 2: tsc 校验**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
npx tsc --noEmit 2>&1 | grep -c "error TS" || echo "0 errors"
```

Expected: 与 Task 4 Step 2 相同或更少。

- [ ] **Step 3: Commit（若有删除）**

```bash
cd /Users/ghj/work/auraboot/auraboot
git add -A web-admin/app/plugins/core-bpm/
git commit -m "refactor(bpm): drop orphaned ApprovalBadge and approvalService exports"
```

---

## Task 9: 回归验证

**Files:** 无

- [ ] **Step 1: 前端编译**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
npx tsc --noEmit 2>&1 | tee /tmp/tsc-final.log
grep -c "error TS" /tmp/tsc-final.log || echo "0 errors"
```

Expected: 0 errors。

- [ ] **Step 2: 后端编译（确认无 Java 侧断引用）**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
./gradlew compileJava 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 全仓最终 grep 验证无残留**

```bash
cd /Users/ghj/work/auraboot
grep -rn "approval-inbox\|ApprovalInbox\|bpm_approval_inbox" \
  --include="*.ts" --include="*.tsx" --include="*.json" --include="*.java" \
  auraboot/ 2>/dev/null
```

Expected: 无输出（历史 plan / spec .md 不算，因为 grep 限定了扩展名）。

- [ ] **Step 4: 环境重置 + 手动冒烟**

```bash
cd /Users/ghj/work/auraboot/auraboot-enterprise
./scripts/reset-and-init.sh 2>&1 | tail -30
```

打开浏览器：
- 登录 → 侧边栏应只有"任务中心"一项待办入口，无"审批任务"。
- 直接访问 `http://localhost:5173/bpm/approval-inbox` → 应显示 404 或空路由页面。
- 访问 `http://localhost:5173/bpm/task-center` → 正常渲染统计卡 + Tabs。

- [ ] **Step 5: 跑 task-center E2E**

```bash
cd /Users/ghj/work/auraboot/auraboot
LOG=/tmp/pw-merge-$(date +%Y%m%d-%H%M%S).log
echo "Log: $LOG"
NO_PROXY=localhost npx --package=@playwright/test playwright test \
  web-admin/tests/e2e/bpm/task-center.spec.ts 2>&1 | tee "$LOG"
grep -E "passed|failed" "$LOG" | tail -5
```

Expected: 所有 task-center 用例通过。若失败，先读日志定位，不要改超时。

- [ ] **Step 6: 最终 commit（若 Task 9 中途补了任何修复）**

```bash
cd /Users/ghj/work/auraboot/auraboot
git status
# 若有变更：
# git add -A && git commit -m "fix(bpm): regression fixups after approval-inbox removal"
```

---

## Self-Review

**Spec coverage 检查：**
- Spec §2 方案 A "删除 ApprovalInbox.tsx / ApprovalInboxPage.tsx / 路由 / 菜单 / 权限" → Task 3/4/5 覆盖 ✓
- Spec §2 方案 A "已办 tab 增加 approved/rejected 过滤" → **本 plan 显式 drop**，原因在"Scope 调整声明"中说明（TaskInstance 缺 outcome 字段）。
- Spec §2 方案 A "E2E 合并" → Task 6 覆盖 ✓
- Spec §3 破坏性变更（不做 301） → Task 4 确实整段删除，未加 redirect ✓
- Spec §4 验收标准 1-6 → Task 9 全部覆盖 ✓
- Spec §6 落地步骤 1-6 → 对应 Task 3-7，其中步骤 1（已办 tab filter）依 Scope 调整声明 skip ✓

**Placeholder scan：** 无 "TBD / TODO / implement later / similar to"。每个 Step 都有具体命令或代码片段或精确定位。

**Type consistency：** 本 plan 无自定义新类型；所有引用的文件 / 行号 / JSON 字段均来自 Task 2 已验证的现状。

**已知依赖外 Task 的决策：** Task 6 Step 1、Task 8 Step 1 依 Task 2 的 grep 结论，plan 显式说明依赖路径，不是 placeholder。
