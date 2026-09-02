# OSS BPM Showcase E2E Suite (`@bpm-showcase`)

业务场景化的 BPM 端到端验证集合(规则 / SLA / 传统工作流)。Scenario SOT 与任务拆解:
workspace `docs/plans/2026-08-28-oss-bpm-showcase-e2e-verification-plan.md`。

## 运行

```bash
cd web-admin
pnpm test:bpm-showcase          # 需先 export 定向 env 契约, 见下
```

定向 env 契约(`PW_SKIP_WEBSERVER=1` 复用已起栈;变量名错误会全量假阴性):

```bash
PLAYWRIGHT_BASE_URL=http://127.0.0.1:<vite> BACKEND_URL=http://127.0.0.1:<be> \
BE_PORT=<be> BFF_PORT=<bff> PG_HOST=127.0.0.1 PG_PORT=5432 PG_USER=<u> PG_DB=<db> PGPASSWORD=<p> \
PW_SKIP_WEBSERVER=1 NO_PROXY=127.0.0.1,localhost
```

顺序纪律:先 `tests/e2e/bpm-smoke/` 全绿,再跑本目录(smoke-first)。
多 run 并行需按 run id 隔离 `PW_STORAGE_DIR` / `PW_ARTIFACT_DIR` / `PW_REPORT_DIR`。

## 场景一览

| Spec | 场景 | 核心覆盖 |
| --- | --- | --- |
| `s1-approval-core.spec.ts` | S1 审批基本盘 | 待办多角色可见性、UI approve 注入 taskResult(Gap #8 Part 1)、空驳回理由拒绝、后端 taskActions fallback(Part 2, API-backed)、无权限反例 |
| `s2-task-redistribution.spec.ts` | S2 任务再分配 | 转办(bob→carol, UI picker)、委托(bob→dave)、候选认领(role 展开, claim, 认领后互斥缺口 pin) |
| `s3-s4-cc-countersign.spec.ts` | S3 抄送 + S4 会签 | 手动抄送链路(产品缺口 pin, 见下)、并行会签三实例、完成条件、加签/减签 |
| `s5-s6-gateways-rules.spec.ts` | S5 网关 + S6 规则 | 并行网关 fork/join 等待、包容网关单/双分支、包容网关零条件命中走 default 兜底(S5.3, 其余分支不激活)、Drools `wd_leave_routing` 按天数分流 + 流程状态页规则 trace UI |
| `s7-sla-deadline.spec.ts` | S7 SLA | NODE 级 SLA record 激活 → 15s 调度推进 warning/overdue → escalate 通知(urge 类型)→ 办结不闭环缺口 pin |
| `s8-grand-tour.spec.ts` | S8 大串联 | 包容双分支 + 财务会签(MI)+ join 收束;carol 走真实 UI 办结 |

## 多账号 / 权限

`_helpers/showcase.ts` 幂等供给 5 个 persona(alice/bob/carol/dave/eve,
`bpm-showcase-*@test.com`)+ `bpm_showcase_member` 角色
(权限:`bpm.process.execute/read`、`bpm.task.read/update`、`bpm.form.update`、
`bpm.process.admin`、菜单 `bpm_management/bpm_process_management/bpm_task_center`)。
S6 另补 `wd-showcase-manager/hr@test.com` 进种子的 `wd_manager`/`wd_hr` 组。

## 产品发现(2026-08-28,由本套件抓出)

1. **已修**:`transferTask`/`delegateTask` 前端发 `{userId}`,后端要
   `targetUserId` → 转办/委托静默无效但报成功(`bpmWorkbenchService.ts` 已改为
   `targetUserId`,S2.1/S2.2 回归保护)。
2. **deferred,需 owner 授权**:手动抄送链路断裂 —— MemberPicker 的 id 是
   pid 字符串,`Number(pid)`→NaN→JSON null → `BpmNotifyController.sendCarbonCopy`
   NPE(500)。S3.1 钉住现状,只读抄送箱断言 BLOCKED。
3. **deferred**:认领后其他角色成员仍可 complete(claim 不强制独占)。S2.3 pin。
4. **deferred**:NODE 级 SLA record 在任务办结后永不完结
   (`SlaRecordService.completeByTaskId` 无调用方)。S7.3 pin。

## 平台事实备注(测试设计依据)

- OSS 无"任意流程发起"独立 UI:通用发起 = DSL `ActionDef(type=bpm)` 或业务表单
  (workflow-demo)。发起步骤一律 API-backed 并在矩阵标注。
- 会签(MI)用 **完成任务(plain complete)** 驱动;approve 注入的
  `_action/taskResult` 变量会干扰 MI join 计数。
- `/api/bpm/orchestration/executions/{id}/timeline` 仅 orchestrated 启动路径有数据;
  完成证据用 `queryInstanceStatus.completedNodes` + `ab_bpm_audit_record`。
- 流程定义 draft DELETE 是软删,唯一键 `(tenant, key, version)` 仍占用 →
  每 run 用唯一 processKey。
- 引擎 assignee/todo 匹配键是 **ab_user.pid(ULID)**,不是数字 id。

## 已知缺口(未测 / blocked)

- S3 只读抄送箱断言:BLOCKED(见发现 2)。
- S1 rollback/withdraw 步骤未单独落用例(控制器/权限已由后端 IT 覆盖,
  UI 路径待后续批次;见 plan §4 S1.5/S1.6 预留行)。
- ~~包容网关 0 分支命中(所有条件为 false)行为未覆盖(需产品语义决策)。~~
  已覆盖:语义定为 default fallback(#1745, 无 default 的 inclusive fork 在
  converter fail-fast),S5.3 以真实引擎验证零命中走 default。
