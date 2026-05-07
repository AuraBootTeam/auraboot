# 2026-05-08 Scheduled Task DSL Page Followups

会话源:owner 问 `http://localhost:5173/p/scheduled_task` 怎么测试 → subagent 浏览器验证发现 6 个问题 → 修了 P0/P1 三个,以下三个降级为后续。

## 已闭合(同会话上线)

| ID | 描述 | Commit |
|---|---|---|
| Fix #2 | RowActions dropdown 初始 (0,0) + 缺 viewport clamp + opacity-0 行 hover 让 trigger 在用户追 portal 时褪色 | `ffbfa171` → merged via `0783f537` on main |
| Fix #3 | DSL 列表页 i18n 泄漏(主标题落到 modelCode / `CREATED_AT` 列头 / toolbar 英文 key 兜底 / 行操作按钮 bare label) | `66f19782` → merged via `d9b0a553` on main |
| Fix #1' | `CommandFieldMapExecutor` 在 `operationType=delete/update` 但缺 `targetRecordId` 时静默 fall-through 到 INSERT(只靠 DB NOT NULL 兜底) | 进行中,branch `fix-delete-defense` |

## ⏸ 待办 — `scheduled_task` 域

### F-1 `timeout_ms` 创建/更新校验不一致(P2)

**症状**:从 detail 页点"编辑"→ 表单预填(timeout_ms 默认 300000)→ 直接提交 update 命令 → 422 `Field 'timeout_ms' exceeds maximum value of 60000`。create 路径 default=300000 通过,update 路径 max=60000 拒绝。任何"原值不动"的更新流程必中招。

**定位**:`auraboot/plugins/platform-admin/config/models.json` 中 `scheduled_task.timeout_ms` 的 `default`(300000)与 `validators.max`(60000)互相矛盾;或 default 来自代码 / form schema 别处。

**修法选项**:
1. 把 max 提到 600000 或更大(timeout 600s 是合理 cron/job 上限)
2. 把 default 调到 60000 以内
3. 区分 cron 任务和 long-running 任务用不同 timeout 上限(过度设计,放弃)

推荐 (1):大多数 LLM/外部调用任务 60s 上限太紧。

**工时**:0.5h(改 model 配置 + 1 条集成测试 + 1 条 E2E 断言更新)

### F-2 字典 `scheduled_task_type` 在新建/列表往返间被请求 ≥800 次(P1 性能)

**症状**:子 agent 验证报告:从列表 → 新建 → 提交 → 回列表的一轮交互,`/api/meta/dict/by-code/scheduled_task_type/data` 被前端打了 800+ 次。`task_type` 字段是 dict-backed enum,**`datasourceId=ds_<ts>_<rand>` 每次重渲染都重抓**,前端 cache key 因 random 失效。

**定位**:`web-admin/app/framework/meta/...` 中 `DictDataSource` / `useFieldDictionary` 相关 hook,grep `datasourceId` 看 random 来源。可能是某个上层组件每次 re-render 都重新生成 datasourceId 当 prop 传下去,导致 cache miss + render-time fetch。

**修法**:
- datasourceId 改为 stable(基于 dict code hash 或直接用 dict code 当 cacheKey,不要时间戳/random)
- 或干脆用 React Query `staleTime` + dictCode 作 queryKey,不依赖 datasourceId
- 验证:打开新建表单一次,Network 面板看 `dict/by-code` 调用应 ≤ 字典数量(每个 dict 一次,非 800+)

**工时**:2h(诊断 + 修 + 加 React Query stable-key 单测)

**风险**:这个 hook 多 DSL 页共用,改动半径大,需要全 DSL 页冒烟。

### F-3 cron `next_run_at` 不计算(P1 产品功能)

**症状**:创建 cron `0 30 2 * *` 任务 → detail 页 `下次运行` 永远显示 `—`。无前端报错,无后端报错,scheduler 也没注册任务。

**两种可能**:
1. 后端 `ScheduledTaskService.create` 命令 handler 只写 row,没调 `recomputeNextRun()` / `scheduler.register(task)`
2. Scheduler 启动时只对 `enabled=true` 行做 register,但创建命令的 enabled default 路径有 race(创建后才 enable,scheduler 已 boot 完)

**定位**:
- `auraboot/platform/src/main/java/com/auraboot/framework/scheduler/` 看 ScheduledTask 的 create handler
- `ScheduledTaskService` / `SchedulerBootstrap`(类似命名)看启动时如何 enroll

**修法假设**:在 create 命令 EFFECT phase 后,绑定一条 binding rule 调用 `SchedulerService.registerOrReload(taskId)`;reload 命令也是。

**工时**:3h(看代码 + 加注册逻辑 + 集成测试触发 trigger 后看到 next_run_at 非空)

### F-4 `CommandFieldMapExecutor.injectExistingJsonbData` SQL 拼接(P1 安全)

**症状**:`platform/src/main/java/com/auraboot/framework/meta/service/impl/CommandFieldMapExecutor.java:452` 区域,`injectExistingJsonbData` 用字符串拼接构造 `whereClause = idEntry.getKey() + " = '" + idEntry.getValue() + "'"`,其中 `idEntry.getValue()` 间接来自 controller 层 `targetRecordId`(用户可控,虽 controller 校验 pid 字符集但不严)。

**风险**:若任何上游放宽 pid 校验或新增 id 字段类型(如 string code),此处会变成 SQL injection 入口。

**修法**:改用参数化 — 已存在 `DynamicDataMapper` 的 `selectByCondition` 风格 API,把 `whereClause` 改成 `Map<String, Object> conditions`(同其他分支)。

**工时**:1h(改 1 个方法 + 1 条集成测试覆盖恶意 pid 不会执行注入语句)

来源:`fix-delete-defense` subagent 顺手发现,与本会话主任务无关但同一文件。

## 关联

- `web-admin/tests/e2e/platform-admin/scheduled-task-dsl.spec.ts` 已覆盖 list → create → detail → edit → delete 全链路;F-1/F-2/F-3 修完应在该 spec 里加对应断言(F-3:create 后 detail 页 `下次运行` 不为 —;F-1:从 detail→edit 流程不再 422;F-2:Network 监听 dict 调用 ≤ N)
- 与 OSS hardcoded `/scheduler` 路径(`tests/e2e/scheduler/scheduler-crud.spec.ts`)是双轨。**长期决定**:hardcoded `/scheduler` 与 DSL `/p/scheduled_task` 应收敛为一个 — 配置优先红线倾向保留 DSL 路径。但 owner 决定何时收敛,本会话不动。

## 触发

- F-1 / F-3 任何后端 SBA 工作时顺手处理(改动小)
- F-2 单独一个 PR,与 dict 渲染统一改造一起做
