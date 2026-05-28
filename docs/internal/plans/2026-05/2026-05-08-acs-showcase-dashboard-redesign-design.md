# ACP Showcase Dashboard 重构设计

- **日期**: 2026-05-08
- **作者**: yaoyi.hz@gmail.com (with Claude)
- **范围**: `auraboot/plugins/acp-showcase/config/dashboards/acs_dashboard.json` 重构 + 配套 i18n / named-queries / E2E
- **类型**: 演示叙事页(showcase storytelling),非运营监控台
- **方向**: A 纯声明式(无前端 tsx 改动)

## 背景

`acs_dashboard.json` 当前只有一个 `smart-rich-text` 欢迎块,文案"ACP Showcase / Agent Control Plane showcase dashboard."。同插件已就绪 5 个 named query(`acs_showcase_kpi` / `acs_showcase_risk_distribution` / `acs_showcase_status_distribution` / `acs_showcase_safety_trend` / `acs_showcase_category_stats`),但 dashboard 没消费。结果是访客打开页面只能看到一行字,完全没把 ACP(Agent Control Plane)的"7 层语义管道 + 引爆点 + 安全阀门 + 全审计"叙事讲出来。

## 目标

让访客在不读文档的前提下,30 秒内通过这一页 dashboard 理解:

1. ACP 在做什么(7 层语义管道)
2. 现在跑的怎么样(KPI、状态、风险分布)
3. 安全阀门在如何兜底(触发趋势、阻塞数)
4. 每一步都可审计(执行日志表)
5. 可以亲手跑一遍(CTA "运行一次 demo 请求")

## 非目标

- 不做运营监控台(SLA / 告警 / oncall 路由不在范围)
- 不做插件代码改动以外的平台核心改动
- 不写定制 widget(如 `acs-pipeline-flow`),保持纯声明式
- 不改 dashboard 框架渲染逻辑

## 信息架构

总宽 12 列(平台默认),`rowHeight=100`,`gap=16`。从上到下:

| 行 | widget | 类型 | xy / wh | 数据源 | 说明 |
|----|--------|------|---------|--------|------|
| 1 | `hero` | `smart-rich-text` | (0,0) 12×2 | inline HTML | 价值主张 + 渐变背景 + 状态徽章 |
| 2 | `kpi_total_requests` | `smart-number-card` | (0,2) 3×2 | `namedQuery: acs_showcase_kpi.total_requests` | 累计请求 |
| 2 | `kpi_success_rate` | `smart-number-card` | (3,2) 3×2 | `acs_showcase_kpi.success_rate` | 成功率 % |
| 2 | `kpi_avg_duration` | `smart-number-card` | (6,2) 3×2 | `acs_showcase_kpi.avg_duration_ms` | 平均耗时 ms |
| 2 | `kpi_safety_triggers` | `smart-number-card` | (9,2) 3×2 | `acs_showcase_kpi.safety_triggers` | 安全阀门触发 |
| 3 | `pipeline_diagram` | `smart-rich-text` | (0,4) 12×4 | inline SVG | 横向 7 层语义管道流程图 |
| 4 | `cta_strip` | `smart-shortcuts` | (0,8) 12×2 | shortcuts 静态配置 | 3 个 CTA: ① 运行一次 demo 请求 ② 查看请求列表 ③ 查看安全阀门规则 |
| 5 | `chart_status` | `smart-bar-chart` | (0,10) 4×4 | `acs_showcase_status_distribution` | 请求状态分布 |
| 5 | `chart_risk` | `smart-pie-chart` | (4,10) 4×4 | `acs_showcase_risk_distribution` | 风险等级分布 |
| 5 | `chart_category` | `smart-combo-chart` | (8,10) 4×4 | `acs_showcase_category_stats` | 分类成败堆叠 |
| 6 | `chart_safety_trend` | `smart-line-chart` | (0,14) 8×4 | `acs_showcase_safety_trend` | 30 天安全阀门触发趋势 |
| 6 | `kpi_pending_approvals` | `smart-number-card` | (8,14) 4×2 | `acs_showcase_kpi.pending_approvals` | 待审批阻塞数 |
| 6 | `kpi_total_cost` | `smart-number-card` | (8,16) 4×2 | `acs_showcase_kpi.total_cost` | 累计成本 USD |
| 7 | `recent_logs` | `smart-table-chart` | (0,18) 12×5 | `acs_showcase_recent_logs`(新增) | 最近 10 条执行审计 |
| 8 | `footer_guide` | `smart-rich-text` | (0,23) 12×2 | inline HTML | 3 步使用导览 + 链接 |

总高度 25 行 ≈ 2516px(含 gap),适合滚动叙事页范式。

### 横向 7 层管道图(SVG 草图)

```
[L5 自然语言输入] ─⚡触发器─▶ [L4 意图解析] ─🛡风控─▶ [L3 能力规划]
                                                          │
[L0 数据写入] ◀─🛡审计─ [L1 工具调用] ◀─🛡熔断器─ [L2 动作执行]
                                                          │
                                            (完成 → 成功 / 失败 / 阻塞)
```

实现要点:
- 6 节点(L5..L0),用 `<rect rx=8>` + `<text>`,首行 3 个 + 折回第二行 3 个,反向箭头
- ⚡(引爆点)= `<text>⚡</text>` 节点间圆点;🛡(安全阀门)= `<text>🛡</text>`
- 配色全部走 CSS variable: `var(--color-primary)` / `var(--color-warning)` / `var(--color-success)` / `var(--color-text-secondary)`,自动暗色适配
- SVG 加 `role="img"` + `<title>$i18n:acs.dashboard.pipeline.svg_title</title>` 满足 a11y(lighthouse ≥ 90)
- 容器 `<div class="...">` 用 Tailwind 内置类(`bg-gradient-to-r`、`rounded-xl`、`shadow-sm`),不引入插件自定义 CSS

### CTA Strip 三个 shortcut

| shortcut | 触发 | 落点 |
|----------|------|------|
| ① 运行一次 demo 请求 | `command: acs:create_demo_request` | 弹 form 让用户填 title + nl_input → submit → 刷新 dashboard |
| ② 查看请求列表 | `link` | `/p/acs_demo_request` |
| ③ 查看安全阀门规则 | `link` | `/p/acs_safety_rule` |

`smart-shortcuts` widget 已支持 `type: command \| link`,无需扩展。

## 视觉语言

- **不另起炉灶**:全程用平台 Tailwind tokens,不引入新 CSS variable
- **图标**:`Rocket / TrendingUp / Clock / ShieldCheck / Inbox / DollarSign`(均在 lucide-react)
- **色板**:status-bar 用 `--color-success / warning / danger`;pie 用平台 chart 默认色板;不指定 `theme.colors`
- **响应式**:1280 / 1920 两种主流宽度都不溢出。SVG 用 `viewBox` + `preserveAspectRatio` 自适应

## i18n

新增 ~22 个 key 在 `acp-showcase/config/i18n.json` 的 `zh-CN` + `en` 两套:

```
acs.dashboard.hero.title
acs.dashboard.hero.subtitle
acs.dashboard.hero.badge_running
acs.dashboard.kpi.total_requests
acs.dashboard.kpi.success_rate
acs.dashboard.kpi.avg_duration
acs.dashboard.kpi.safety_triggers
acs.dashboard.kpi.pending_approvals
acs.dashboard.kpi.total_cost
acs.dashboard.pipeline.svg_title
acs.dashboard.pipeline.layers.l5
... (l4 / l3 / l2 / l1 / l0)
acs.dashboard.pipeline.legend_trigger
acs.dashboard.pipeline.legend_valve
acs.dashboard.cta.run_demo
acs.dashboard.cta.view_requests
acs.dashboard.cta.view_rules
acs.dashboard.chart.status_title
acs.dashboard.chart.risk_title
acs.dashboard.chart.category_title
acs.dashboard.chart.safety_trend_title
acs.dashboard.recent_logs.title
acs.dashboard.recent_logs.col_time
acs.dashboard.recent_logs.col_layer
acs.dashboard.recent_logs.col_action
acs.dashboard.recent_logs.col_status
acs.dashboard.recent_logs.col_risk
acs.dashboard.recent_logs.col_valve
acs.dashboard.recent_logs.col_duration
acs.dashboard.footer.title
acs.dashboard.footer.step1 / step2 / step3
acs.dashboard.empty.no_data_hint
```

DSL JSON 内**只放 `$i18n:key`**,严禁中英文硬编码(红线 #3)。

## 数据消费

### 已有 named queries(零改动)

- `acs_showcase_kpi`(6 字段) → 6 个 number card
- `acs_showcase_status_distribution` → bar chart
- `acs_showcase_risk_distribution` → pie chart
- `acs_showcase_category_stats` → combo chart
- `acs_showcase_safety_trend` → line chart

### 新增 1 个 named query

```sql
-- code: acs_showcase_recent_logs
SELECT
  acs_log_timestamp     AS log_time,
  acs_log_layer         AS layer,
  acs_log_action_type   AS action_type,
  acs_log_status        AS status,
  acs_log_risk_level    AS risk_level,
  acs_log_safety_triggered AS safety_triggered,
  acs_log_duration_ms   AS duration_ms
FROM mt_acs_execution_log
WHERE tenant_id = #{params.tenantId}
ORDER BY acs_log_timestamp DESC
LIMIT 10
```

加在 `named-queries.json`,outputFields 6 项。**不直接用 dynamic model 排序+limit** 因为 dashboard table widget 对模型直查的 limit/order 支持稳定性参差,named query 一致性更好。

## 空 / 加载 / 错误态

- **加载态**:平台 widget 自带 skeleton,无需额外
- **空数据(全 0,即未跑过任何 demo)**:Hero 下方加一行 `acs.dashboard.empty.no_data_hint`("还没有数据 — 点击下方 CTA '运行一次 demo 请求'"),引导跑第一条
- **错误态**:widget 自带错误样式;named query 失败显示具体错误,不静默吞(红线 #8)

## E2E 覆盖

新建 `auraboot/web-admin/tests/e2e/acs-showcase-dashboard.spec.ts`:

1. **导航**:登录 → 侧边栏点 dashboards 菜单 → tab 切到 `acs_dashboard`(不用 `page.goto` 直达,红线)
2. **渲染断言**:14 个 widget 全部渲染(用 `data-widget-id` 选择器)
3. **KPI 真数据**:6 个 number card 显示数字(`/^\d/` 正则,不允许空 / `--`)
4. **管道 SVG**:断言 6 个 layer 节点存在(`svg [data-layer="L0"]` ... `L5`),title role 满足
5. **图表数据**:bar / pie / combo / line 各断言 ≥ 1 个数据点 DOM(`recharts-bar / pie-sector / line-curve`)
6. **审计表**:`tbody tr` 数量 ≤ 10,且 ≥ 1
7. **CTA 互动**:点 "运行一次 demo 请求" → 弹 form → 填值 → submit → 列表 +1(走 UI,不走 API 兜底,红线 #2)
8. **i18n 不泄漏**:全页文本 `expect(text).not.toMatch(/\$i18n:/)`
9. **空状态**:在隔离 tenant 下打开,断言 `acs.dashboard.empty.no_data_hint` 可见(可选,看跑 spec 的成本)

走金标准模板 `web-admin/tests/e2e/templates/thr-leave-request-lifecycle.spec.ts` 的 14 维度子集(D1 导航 / D3 渲染 / D5 数据真值 / D9 互动 / D12 i18n)。

## 改动清单

| 文件 | 动作 | 估算 |
|------|------|------|
| `auraboot/plugins/acp-showcase/config/dashboards/acs_dashboard.json` | 整体重写,从 1 widget 扩到 14 widget | ~250 行 |
| `auraboot/plugins/acp-showcase/config/i18n.json` | 新增 ~22 key × 2 语言 | ~50 行 |
| `auraboot/plugins/acp-showcase/config/named-queries.json` | 加 `acs_showcase_recent_logs` | ~15 行 |
| `auraboot/web-admin/tests/e2e/acs-showcase-dashboard.spec.ts` | 新建 | ~180 行 |

**不动**:任何前端 tsx、widget registry、平台核心。

## 验收标准

1. 浏览器打开 `/dashboards?code=acs_dashboard`,14 widget 全部渲染、有数据(浏览器人肉验证 + screenshot)
2. 7 层 SVG 在 1280 / 1920 两种宽度下不溢出、不重叠
3. 切到 en locale 后所有文案为英文,DOM 内无 `$i18n:` 残留
4. E2E spec 全 pass(单测 + 真数据断言,无 PUT-API 兜底,无 retries 兜底)
5. 暗色主题切换后视觉无降级
6. lighthouse a11y ≥ 90(SVG 加 role/title)
7. CTA "运行一次 demo 请求" 端到端可走完(创建 → 提交 → dashboard 数据 +1)

## 风险与回滚

- **风险**:`smart-shortcuts` widget 对 command-with-form 触发的支持若不完整,CTA ① 可能要降级为 link 跳到 `/p/acs_demo_request/new`。**预诊断动作**:写代码前先 grep `smart-shortcuts` 实现,确认 `type: command + commandCode` 是否走 ConfirmAction / ModalForm
- **风险**:rich-text widget 对内嵌 SVG 是否做了 sanitize,会不会把 `<svg>` 过滤掉。**预诊断动作**:本地拼一段 SVG 塞进 rich-text widget 跑一次,确认渲染
- **回滚**:整个改动只是 4 个文件,git revert 即可

## 开放项(已通过 Q&A 拍板)

- 7 层管道:**横向**(宽屏更易读)
- 审计表:**10 条**(信息密度合适)
- Hero CTA:**加**(可互动 showcase 比纯展示更具冲击力)

## 后续(out of scope)

- 若 ACP Showcase 之后被频繁要求加联动(hover 高亮 / 点节点跳详情),再启动"提取 `pipeline-flow` 平台 widget"项目;现在不预演
- 若 demo 数据缺失成为高频抱怨,补 `seed-data.sh` 脚本(本次先用 footer 文案引导手跑)
