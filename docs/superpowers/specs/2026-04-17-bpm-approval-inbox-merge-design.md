# BPM 审批任务页融合设计

- 日期：2026-04-17
- 范围：`auraboot/` OSS core-bpm 插件
- 目标：消除 `/bpm/approval-inbox` 与 `/bpm/task-center` 的功能重复

## 1. 现状

| 维度 | `/bpm/approval-inbox`（审批任务） | `/bpm/task-center`（任务中心） |
|------|-----------------------------------|-------------------------------|
| 组件 | `components/ApprovalInbox.tsx`（213 行） | `components/TaskCenter.tsx`（239 行） |
| UI | 卡片列表 | 统计卡 + 表格 + 批量操作 |
| Tabs | Pending / Approved / Rejected / All | 待办 / 已办 / 我发起的 / 抄送给我 / 催办提醒 |
| 数据源 | `approvalService`：`getMyPendingTasks` / `getMyHistory` → `/api/approvals/*` | `bpmWorkbenchService` via `useTaskCenter` → `/api/bpm/workbench/*` |
| 动作 | 打开 `BpmTaskDrawer` 审批 | 审批 / 驳回 / 委派 / 转办 / 加签 / 减签 / 回退 / 终止 / 催办 / 抄送 |
| SLA | ✗ | ✓（`slaWarningTaskIds`） |
| 搜索 | ✗ | ✓ |
| 批量 | ✗ | ✓（批量通过 / 批量驳回） |
| 菜单 | `bpm_approval_inbox` orderNo=23 | `bpm_task_center` orderNo=22 |
| 权限 | `bpm_approval_inbox` | `bpm_task_center` |
| E2E | `tests/e2e/approval/approval-*.spec.ts` | `tests/e2e/bpm/task-center.spec.ts` |

**判断**：TaskCenter 功能是 ApprovalInbox 的严格超集。ApprovalInbox 唯一独特的维度是 "按审批结果（approved/rejected）过滤历史"——这个维度可以 0 成本地吸收进 TaskCenter 的 "已办" tab。

## 2. 设计决策

### 方案 A（采纳）：删除 ApprovalInbox，TaskCenter 吸收过滤维度

- 删除 `ApprovalInbox.tsx` / `ApprovalInboxPage.tsx` / `/bpm/approval-inbox` 路由 / `bpm_approval_inbox` 菜单 + 权限条目。
- 在 TaskCenter 的 "已办" tab 增加一个 `Select`：全部 / 已通过 / 已驳回，复用现有 `filteredCompletedTasks`，加一层 `status` 过滤。
- 保留 `approvalService.getMyHistory` 暂不删除（仍被 mobile / 其它 drawer 可能使用，后续核查再清理）。
- 菜单 `bpm_task_center` 改名为 "审批任务"（合并后这个名字更贴近终端用户认知），或维持 "任务中心"——由后续 UX 决定，不在本 spec 硬决定。
- 两套 E2E 合并：`approval-*` 保留的断言迁移到 `task-center.spec.ts`，原 spec 删除。

### 方案 B（否决）：保留 ApprovalInbox 作为 "轻量视图"

- 理由：卡片 UI 对非 admin 用户可能更友好。
- 否决理由：
  1. 当前没有证据表明用户需要 "轻量视图"，属于假设需求，违反 YAGNI。
  2. 维护双入口 = 双份 E2E + 双份菜单认知负担 + 双份后端 API。
  3. 如果后续确实需要轻量卡片视图，应作为 TaskCenter 内部的 "视图切换"（表格 ↔ 卡片），而不是独立路由。

### 方案 C（否决）：反向——删除 TaskCenter，扩展 ApprovalInbox

- 否决：TaskCenter 已包含委派/转办/加签/催办等平台级 BPM 能力，卡片 UI 容纳这些动作会很挤；重写成本高于方案 A。

## 3. 破坏性变更声明

遵循 `dev_stage_breaking_ok` 策略：

- 不做 301 重定向 / 不保留旧路由 shim。
- 直接删除菜单、权限、路由、组件、E2E。
- 老书签指向 `/bpm/approval-inbox` 将 404——开发阶段可接受。
- `permissions.json` 中 `bpm_approval_inbox` 权限码直接删除；已分配过此权限的角色记录在 reset 后自然消失（OSS 有独立 reset 脚本）。

## 4. 验收标准

1. `/bpm/approval-inbox` 返回 404，菜单项不再出现。
2. `/bpm/task-center` 的 "已办" tab 支持按 approved/rejected/all 过滤，默认 all。
3. `rg "approval-inbox|ApprovalInbox"` 在 `auraboot/` 下仅剩历史 plan 文档中的引用，代码/配置/测试零残留。
4. `tests/e2e/bpm/task-center.spec.ts` 覆盖：待办列表、已办 + 过滤、批量通过、批量驳回 4 条金标准断言。
5. `./scripts/reset-and-init.sh` 后前端无红屏，菜单渲染正常。
6. `./gradlew compileJava` 与 `npx tsc --noEmit` 零新增错误。

## 5. 不在本 spec 范围

- `approvalService` 与 `bpmWorkbenchService` 的 API 层统一（两个后端 Controller 合并）——单独议题。
- 移动端 inbox（mobile-only，OSS/Enterprise 边界内仍保留）。
- TaskCenter 的视图切换（表格 ↔ 卡片）——YAGNI。

## 6. 落地步骤概览（正式 plan 留到 writing-plans 阶段）

1. 前端：TaskCenter 已办 tab 增加 status Select + 过滤逻辑。
2. 前端：删除 `ApprovalInbox.tsx` / `ApprovalInboxPage.tsx` / `resources.ts` 中的 route 项。
3. 插件配置：删除 `menus.json` 与 `permissions.json` 中 `bpm_approval_inbox` 条目。
4. 测试：迁移并删除 `tests/e2e/approval/approval-*.spec.ts`，断言落到 `task-center.spec.ts`。
5. 文档：`docs/guides/bpm-workflows.md` 与 `system-reference/subsystems/12-审批工作流系统.md` 去掉 approval-inbox 路径引用。
6. `reset-and-init.sh` 回归一次，`pnpm test --grep task-center` 通过。
