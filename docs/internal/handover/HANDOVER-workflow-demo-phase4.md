# HANDOVER — workflow-demo Phase 4 E2E

## 接续点

分支 `feat/workflow-demo-phase1`（12 commit，已 push），全链路 API 级验证通过。需要写 4 个 Playwright E2E spec。

## 环境状态

- 后端 6443 可能已停（上次在 worktree 跑的），需要重启
- worktree 路径：`auraboot/.worktrees/workflow-demo-phase2`（branch `feat/workflow-demo-phase1`）
- 前端 5173 可能需要单独启动
- DB 里已有 workflow-demo 插件数据 + admin 绑定了 wd_manager/wd_hr 角色 + balance 记录
- 如果环境不干净，从 worktree 跑 `./scripts/oss-reset-and-init.sh` 重置

## 设计文档

`auraboot/docs/plans/2026-04/2026-04-15-workflow-demo-plugin-design.md` — §11 定义了 4 个 E2E spec，§15 记录了所有架构决策。

## 4 个 E2E Spec

放在 `auraboot/web-admin/tests/e2e/workflow-demo/`（需要在 worktree 对应路径创建）。

| spec | 场景 | 关键断言 |
|---|---|---|
| `wd-leave-short-manager.spec.ts` | alice(admin) 提交 2 天病假 → Drools 路由到主管 → 通过 → 状态=approved + 通知 | 侧边栏导航、表单填写、列表可见、详情页字段值、审批操作、状态变更 toast |
| `wd-leave-long-hr.spec.ts` | 提交 5 天年假 → Drools 路由到 HR → 驳回 → 状态=rejected | 同上 + 验证走 HR 审批路径 |
| `wd-leave-rule-block.spec.ts` | 余额 0 提交 3 天年假 → 前端弹错 annual_leave_insufficient → 状态保持 draft | 错误 toast 断言、无流程实例 |
| `wd-leave-sla-escalation.spec.ts` | 提交后 30s 不审批 → 等 sla_escalated 通知出现 + 审批人改派 | expect().toBeVisible({ timeout: 45000 }) |

## 金标准参照

`web-admin/tests/e2e/templates/thr-leave-request-lifecycle.spec.ts` — 14 维度断言模板（D1-D14）。

## 已验证的 API 调用形式

```bash
# create draft
POST /api/meta/commands/execute/wd:create_leave_request
{"payload":{"wd_req_code":"...","wd_req_applicant":"<userId>","wd_req_type":"annual","wd_req_start_date":"2026-04-20","wd_req_end_date":"2026-04-21","wd_req_days":2,"wd_req_reason":"..."}}

# submit (preActions + start_process)
POST /api/meta/commands/execute/wd:submit_leave_request
{"targetRecordId":"<recordId>","operationType":"UPDATE","payload":{"wd_req_applicant":"<userId>","wd_req_type":"annual","wd_req_days":2,"wd_req_attachments":[]}}

# approve
POST /api/bpm/tasks/<taskId>/approve
{"comment":"...","variables":{"taskResult":"approved"}}

# reject
POST /api/bpm/tasks/<taskId>/reject
{"comment":"...","variables":{"taskResult":"rejected"}}
```

## 测试账号

- admin@auraboot.com / Test2026x（已绑 wd_manager + wd_hr）
- admin userId: 302959828878364672
- admin memberId: 302959828941279232
- tenantId: 302959828911919104

## 已知限制

- `title` 字段存储了未解析的 `${payload.wd_req_code}`（PostExecutionPhase placeholder resolver 问题，不影响流程但详情页会显示原始模板）
- `days` 在 se_variable_instance 里 field_string_value 为空（Integer 存在 field_long_value 列），但不影响功能
- Playwright 跑 OSS 用 `auraboot/scripts/oss-test.sh`，不用 `pnpm test`
- 菜单路径：`/p/wd_leave_request`（自动加 _list）

## 不要做

- 不要修改已有的平台 Java 代码（Phase 3b 已冻结）
- 不要增加 timeout 来修 E2E 失败（找根因）
- 不要用 page.goto 直达（必须从侧边栏菜单导航）
- 不要在 afterAll 里做清理
