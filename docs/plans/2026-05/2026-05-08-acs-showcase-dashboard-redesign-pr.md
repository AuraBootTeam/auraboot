# PR: ACP Showcase Dashboard Redesign

**Branch:** `feat/acs-dashboard-redesign`
**Base:** `main` (12 commit ahead)

> 本 PR 起草于 dev stack 离线状态。**runtime 验证未跑**(8 项 deferred,见底部),不建议在跑过本地浏览器 + Playwright 之前 fast-forward 到 main。

## 摘要

把 ACP Showcase dashboard 从 1 个欢迎 rich-text widget 重做成 15 widget 的演示叙事页:7 层语义管道横向 SVG 流程图、6 KPI 卡片(累计请求 / 成功率 / 平均耗时 / 安全阀门触发 / 待审批阻塞 / 累计成本)、3 张分布图(状态 / 风险 / 分类成败)、30 天阀门趋势线、Top 10 执行审计表、3 个 CTA shortcut(运行 demo / 查列表 / 查规则)、footer 三步使用指引。

伴随 5 项**通用平台微改**(后续任意 dashboard 可受益),非 ACP-only 黑魔法。

## 改动范围

### 平台 (5 commit)

| 文件 | 加什么 |
|---|---|
| `core-dashboard/components/WidgetRenderer.tsx` | 每个 widget 包 `<div data-widget-id={widget.id}>` (E2E 钩子) |
| `framework/meta/utils/sanitizeHtml.ts` | DOMPurify 放行 SVG 14 tags + 30+ presentation attrs + `ALLOW_DATA_ATTR: true` |
| `framework/smart/components/charts/SmartNumberCard.tsx` | `metricField?` 选 named query 输出列;`prefix?` 配合 suffix |
| `framework/smart/components/charts/SmartTableChart.tsx` | `columns?: [{field, label}]` 显式配,fallback 到 auto-derive |

### 插件 (5 commit)

| 文件 | 改动 |
|---|---|
| `plugins/acp-showcase/config/dashboards/acs_dashboard.json` | 1→15 widget(分两个 commit:top-half / bottom-half),后续微调 schema |
| `plugins/acp-showcase/config/named-queries.json` | +1 query `acs_showcase_recent_logs`(LIMIT 10 排序最新) |
| `plugins/acp-showcase/config/i18n.json` | +39 key 共 78 翻译(`acs.dashboard.*`,zh-CN + en-US) |

### 测试 (1 commit)

| 文件 | 内容 |
|---|---|
| `web-admin/tests/e2e/acs-showcase-dashboard.spec.ts` | 1 test,covers nav-from-sidebar / 15 widget 可见 / 6 KPI 数字非空 / 6 SVG layer 节点 / 4 chart 数据 shape / table ≤10 行 / 无 `$i18n:` 残留 / CTA 跳 `/p/acs_demo_request/new` |

### 文档 (3 commit)

设计 / 实施计划 / 本 PR 描述。

## 设计决策

3 个开放项已在 brainstorming 阶段拍板:
- **7 层管道横向布局**(宽屏更易读,而非金字塔)
- **审计表 10 行**(信息密度合适,而非 20)
- **加 CTA "运行一次 demo 请求"**(可互动 > 纯展示,但 `smart-shortcuts` 限制为 link,所以路由到 `/p/acs_demo_request/new`)

预诊断扩出来的 4 项:
- **`smart-rich-text` 经 DOMPurify** → 加 SVG + data-* allowlist(`a7130d5b`)
- **`WidgetRenderer` 不带 `data-widget-id`** → 加 wrapper div(`45bad4fc`)
- **`SmartNumberCard` 无 `metricField` / `prefix`** → 加 props(`743d05b8`)
- **`SmartTableChart` 无 `columns` 配** → 加 props(`293d69df`)

## 静态校验通过

- [x] 所有 JSON parse OK
- [x] dashboard JSON 共 15 widget,assertion script 全通过
- [x] `$i18n:` 引用 ↔ 定义 cross-check 零未匹配
- [x] `npx tsc --noEmit` 对 5 个改动文件零新增错误
- [x] 无任何 TODO / 占位符 / `$i18n:` 硬编码 / inline `bindingRules`

## 仍需 dev stack 验证 (8 项 deferred)

按 testing-e2e-web 红线 #2 "完成度声明纪律",合 main 前必须跑通:

1. NamedQuery 响应 `meta.dimensions/metrics` 划分(影响 combo-chart `metricIndex` 对位)
2. 6 KPI 卡复用单 `acs_showcase_kpi` 真显示 6 个不同数字
3. 7 层 SVG sanitizer 放行后渲染完整(markers / data-layer / 文字)
4. CTA `/p/acs_demo_request/new` 路由存在 + 表单有 i18n label
5. 暗色主题下 pipeline SVG 对比度可读(目前用固定 hex)
6. 1280 / 1920 视宽 SVG `viewBox` 自适应不溢出
7. recharts DOM 类名兼容 E2E selector(`.recharts-bar-rectangle` 等)
8. Lighthouse a11y ≥ 90(SVG `<title>` + `role="img"` 已加)

## 测试计划

```bash
# 本 worktree
cd /Users/ghj/work/auraboot/auraboot/.worktrees/acs-dashboard-redesign

# 1. 重导入插件(让 39 i18n key + 新 named query + 新 dashboard JSON 落库)
aura plugins import plugins/acp-showcase
# 或 ./gradlew :platform:bootRun -Dauraboot.plugins.import=plugins/acp-showcase

# 2. 浏览器人肉验证 — http://localhost:5173/dashboards?code=acs_dashboard
#    走 8 项 verification list

# 3. E2E
LOG=/tmp/pw-acs-$(date +%Y%m%d-%H%M%S).log
npx playwright test web-admin/tests/e2e/acs-showcase-dashboard.spec.ts --workers=1 --reporter=line 2>&1 | tee "$LOG"

# 4. 真到绿后 fast-forward
git checkout main && git merge --ff-only feat/acs-dashboard-redesign && git push origin main
```

## 回滚

12 commit 全部 atomic;若任何项目验证失败,可 revert 单个 commit 而不破坏其他改动:
- 平台 4 commit(`45bad4fc` / `a7130d5b` / `743d05b8` / `293d69df`)— 通用价值,即使 ACP dashboard 抛弃也建议保留
- 插件 5 commit — 集中在 `plugins/acp-showcase/`,可一并 revert
- E2E 1 commit — 独立可 revert

## 关联

- Spec: `docs/plans/2026-05/2026-05-08-acs-showcase-dashboard-redesign-design.md`
- Plan: `docs/plans/2026-05/2026-05-08-acs-showcase-dashboard-redesign-plan.md`
