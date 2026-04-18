# Workflow Designer & workflow-demo E2E — 实施产物与发现

- 日期:2026-04-19
- 分支:`feat/wf-designer-e2e`(worktree `auraboot-wf-e2e`)
- 起点:origin/main @ `16a465b6`
- 计划:`auraboot-enterprise/docs/superpowers/plans/2026-04-19-workflow-designer-e2e.md`

## 交付件

### 1. 测试 hook(生产代码小改)
- `web-admin/app/plugins/core-designer/components/bpmn-designer/testHooks.ts`(新增,dev-only)
- `web-admin/app/plugins/core-designer/components/bpmn-designer/BPMNDesigner.tsx`(`useEffect` 挂载钩子 + cleanup)
- 钩子仅在 `import.meta.env.DEV=true` 暴露 `window.__bpmDesigner`,生产环境不泄露

### 2. Playwright helper 层
| 文件 | 职责 |
|------|------|
| `web-admin/tests/helpers/designer-dsl.ts` | openDesigner / addNode / connect / configureNode / saveProcess / deployProcess |
| `web-admin/tests/helpers/bpm-assertions.ts` | assertDesignerJson / assertBpmnXml / startInstanceAndAdvance 三层断言 |
| `web-admin/tests/helpers/wd-fixtures.ts` | loginAs / ensureRoleUsers / createLeaveApplicant / setLeaveBalance / submitLeaveRequest / processTask |

### 3. 运行基础设施
- `scripts/run-wf-e2e.sh` — 薄封装,代理到既有 `scripts/oss-test.sh`(满足"never use pnpm test for OSS"红线)
- `oss-scope.json` 注册新目录 `tests/e2e/bpm-smoke/**`、`tests/e2e/bpm-designer/**`

### 4. 12 条 spec

#### 设计时(6)
| Spec | 覆盖 | 状态 |
|------|------|------|
| designer-usertask-form | userTask + formBinding + fieldPermissions | ✅ |
| designer-gateway-condition | exclusive/parallel/inclusive + conditionExpression | ✅ |
| designer-servicetask-command | serviceTask + Command 绑定 | ✅(L3 限于生命周期) |
| designer-ruletask-drools | ruleTask + Drools 路由 | ✅ |
| designer-callactivity | callActivity + 父子变量映射 | ✅(L3 限于生命周期) |
| designer-sla-panel | SLA 配置(CRUD + L2)+ 运行时注册 | 🟡 L3 fixme(调度器未接线) |

#### 运行时(5)
| Spec | 场景 | 状态 |
|------|------|------|
| wd-leave-short-manager (R1) | 短假→主管通过→approved | ✅ |
| wd-leave-long-hr (R2) | 长假→Drools 分派 HR→通过→approved | ✅ |
| wd-leave-sla-escalation (R3) | SLA 超时升级 | 🟡 fixme |
| wd-leave-reject (R4) | 驳回→rejected | ✅ |
| wd-leave-cancel (R5) | 申请人撤回→cancelled | 🟡 fixme |

#### 贯通冒烟(1)
| Spec | 覆盖 |
|------|------|
| wf-end-to-end-smoke | 设计器→部署→运行 单 test 串起 L1/L2/L3 |

---

## 实施中暴露的产品层 gap(未修复,需单独立项)

### A. SLA 运行时调度未接线 — 影响 R3、D6
- **现状:** `SlaSchedulerService` 扫描 `ab_sla_record`,但 `SlaRecordService.createRecord()` 零调用者
- **影响:** 任务激活时无人创建 SLA 记录,调度器空转,升级永远不触发
- **unblock 方案:** 在 `TaskService.activate()` 或 `BpmNodeHookService.onTaskStart()` 添加 `SlaRecordService.createRecord(task, matchingConfig)`
- **spec 状态:** R3 + D6-L3b `test.fixme`,带精确 unblocker 注释

### B. SLA 无设计器面板 — 影响 D6
- **现状:** SLA 通过独立 `/api/bpm/sla-configs` CRUD 管理,userTask 节点数据无 slaKey 字段
- **影响:** 用户必须离开设计器,跳到 SLA 管理页才能配置 — UX 割裂
- **unblock 方案:** 设计器属性面板新增 SLA 区段(PropertySchema),保存时自动写/更新 sla-configs 表并关联 targetKey

### C. 无 process 变量读取端点 — 影响 D3、D5
- **现状:** `GET /api/bpm/process-instances/{id}/variables` 不存在
- **影响:** serviceTask(D3)、callActivity(D5)的变量传递与输出只能生命周期级别验证,无法比对变量值
- **unblock 方案:** 在 `ProcessInstanceController` 增加 variables 查询端点(只读)

### D. CommandServiceTaskDelegate 依赖 `_chain_nodes` — 影响 D3
- **现状:** serviceTask→Command 绑定在 standalone 启动时需要手动注入 `_chain_nodes` 结构(CommandChainService 设计遗留)
- **影响:** 设计器用户看不到这种隐性要求,第一次部署 + 启动必然踩坑
- **unblock 方案:** 让 `CommandServiceTaskDelegate` 兼容无 chain 上下文的 standalone 调用;或在转换器把 chain_nodes 一起写入部署时默认变量

### E. `rule-task` 节点无前端 palette — 影响 D4
- **现状:** 后端 `BpmServiceTaskConstants.NODE_TYPE_RULE_TASK = "rule-task"` 可识别,但前端 `BPMNNodeType` enum 未列
- **影响:** 设计器面板无法拖出 rule-task,只能通过测试 hook 或 API 创建
- **unblock 方案:** `BPMNNodeType` 枚举与 `NODE_PALETTE` 常量各补一项 `RULE_TASK = 'rule-task'`

### F. workflow-demo 插件缺 cancel 能力 — 影响 R5
- **现状:** 无 `wd:cancel_leave_request` 命令,无撤回按钮,`wd_req_status` 字典无 cancelled 项,流程未声明 withdrawPolicy
- **unblock 方案(清单,详见 R5 spec 头部注释):**
  - A. `processes.json` 设 `withdrawPolicy: "loose"`
  - B. 新增 `wd:cancel_leave_request` 命令(postAction 写 `wd_req_status='cancelled'`)
  - C. `dicts.json` `wd_leave_status` 加 cancelled
  - D. `wd_leave_request_detail` 页面工具栏加撤回按钮(条件显示:state='submitted'/'approving')
  - E. 审批规则中 cancelled 作为终态

---

## 已修复的红线违规

| 次 | 位置 | 问题 |
|---|------|------|
| 1 | designer-dsl.ts | API 响应多路径 fallback → 单 canonical 路径 + 抛错 |
| 2 | bpm-assertions.ts | 多路径 fallback + 空字符串 `.includes` 静默通过 → 显式字段名 + 抛错 |
| 3 | wd-fixtures.ts | 表单字段 `isVisible(...).catch(() => false)` 静默跳过 + recordId 多路径 → `expect(...).toBeVisible` + 单路径 |
| 4 | D2 spec | 错误目录 `tests/e2e/bpm/` + 引用不存在的 `_helpers/bpm-lifecycle` → 移动到 bpm-designer/ + 改用真实 helper |

---

## 执行前置(下一次跑起来前需做)

1. 在 OSS 仓启动后端 + 前端(见 README `pnpm dev:full`、后端 bootRun)
2. 跑 `./scripts/oss-reset-and-init.sh`(AURABOOT_BOOTSTRAP_ENABLED=false)
3. `./scripts/run-wf-e2e.sh smoke`(先跑贯通,验证 helper + 设计器 hook 在真实环境活着)
4. `./scripts/run-wf-e2e.sh designer`(6 条设计时 spec)
5. `./scripts/run-wf-e2e.sh runtime`(5 条运行时 spec,其中 R3/R5 fixme 预计标 SKIP-LIKE)
6. 日志自动归档到 `/tmp/pw-oss-*.log`

## Out of scope(按 spec §5.5)

本轮未覆盖:定时器/边界事件、多实例 MI、设计器画布美化、非 web 平台、流程版本升级/迁移在途实例。
