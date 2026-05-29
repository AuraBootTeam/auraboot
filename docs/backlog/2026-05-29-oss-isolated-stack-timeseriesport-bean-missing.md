# OSS isolated stack 缺 `TimeSeriesPort` bean — 阻塞所有 OSS E2E

**Status**: OPEN
**Priority**: P1 — 阻塞所有需 OSS isolated stack 的 E2E (B2c phase3 batch2+ / B2d / B1 / 其他 designer/automation/bpm 改动验证)
**Filed**: 2026-05-29
**Discovered by**: B2c phase3 batch1 subagent (PR #342) 在跑 bpm-workflow.spec.ts gate 时

## 症状

`COMPOSE_PROJECT_NAME=auraboot-b2c-p3 ./scripts/dev/start-isolated.sh --e2e --port-offset 30` 后 backend 启动失败,Spring 容器报:

```
No qualifying bean of type 'TimeSeriesPort'
```

backend 进程退出,Vite/PG/Redis 起得来但无后端可连,E2E `did-not-run`,归类 `environment-invalid`(AGENTS.md §2.1)。

## 怀疑根因

- 2026-05-28 #335 `feat(iot): platform-side TimeSeriesQueryController + real TDengine IT (M1.E.2a)` 落 main 时,引入了某个对 `TimeSeriesPort` 的强依赖(可能在 platform 而非 enterprise plugin 内)
- OSS isolated stack 的 plugin profile **不加载 IoT plugin** → TimeSeriesPort 实现类(在 IoT plugin)缺失 → @Autowired required=true 失败
- 应该:`TimeSeriesPort` 在 platform 内有 `@ConditionalOnMissingBean` 兜底空实现,或者 controller / service 改 `@Autowired(required=false)`,或者只在 IoT plugin profile 启用 controller

## 复现命令

```bash
cd <oss-worktree>
COMPOSE_PROJECT_NAME=test-tsport ./scripts/dev/start-isolated.sh --e2e --port-offset 40 --wait
# 等 ~3 min,backend 进程退出
# 日志: docker logs test-tsport-backend-1 | grep "No qualifying bean"
```

## 影响范围

- B2c phase3 batch2 (BPMNCanvas / BPMNToolbar / BPMNPropertyPanel 迁移) — 需 E2E gate 验证 cross-store 行为
- B2d page cutover — 入口切换必须 E2E 真验证
- B1 真黄金 E2E — 早就 BLOCKED_WITH_OWNER 同根因
- 任何 OSS designer / automation / bpm 改动想跑真 E2E 都被卡

## 修复路径(任一)

1. **最快**:`TimeSeriesQueryController` 加 `@ConditionalOnBean(TimeSeriesPort.class)` 或 `@Autowired(required=false)`
2. **正确**:OSS 在 platform 提供 `TimeSeriesPort` no-op 默认实现 + `@ConditionalOnMissingBean`,IoT plugin 提供 TDengine 实现覆盖
3. **运行时**:OSS isolated stack 的 plugin profile 加载一个 stub TimeSeriesPort plugin

## 推荐 owner

iot industry base 项目负责人(memory canonical project_iot_industry_base_2026_05_28.md)+ platform owner 协同决策。

## 临时绕过

无 — OSS isolated stack 必须能起,unit/render-only 覆盖不足以验证 cross-store 行为(尤其 `.setState()` shim / `window.__bpmnDesignerStore` test hook 等需真渲染 + 真后端)。
