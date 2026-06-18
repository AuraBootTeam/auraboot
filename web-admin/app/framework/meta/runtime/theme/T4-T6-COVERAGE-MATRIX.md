# T4–T6 renderer interaction-upgrade coverage matrix (§3/§4/§5)

> Honest audit (2026-06-18) of the backlog P1 "渲染器视觉 + 交互升级" against
> standard §3 (list) / §4 (form) / §5 (detail). Earlier work (#733/#735/#738)
> token-ified the renderers + golden-verified they render; THIS pass audits each
> action point for spec-conformance and builds the genuine gaps (TDD + golden).
> State: ✅ exists+conformant · 🔶 exists, needs token/spec fix · ❌ missing.

## §3 — list (`ListPageContent` / `ListTable` / `CellRendererRegistry`)

| Action point                                                              | State | Action                                                                                                            |
| ------------------------------------------------------------------------- | ----- | ----------------------------------------------------------------------------------------------------------------- |
| 视图工具栏 (Airtable) — view switch / sort / fields / filter / row-height | ✅    | exists (ListToolbar)                                                                                              |
| 预设视图 (我的记录/今日/本周)                                             | ✅    | T8 (#743)                                                                                                         |
| 排序 单/多列, 显隐, 列宽, 冻结, 拖表头换序                                | ✅    | exists (DraggableColumnHeader etc.)                                                                               |
| 自动保存到当前视图 (logic)                                                | ✅    | `ensureViewAndUpdateConfig`→`autoSave`                                                                            |
| **「已保存到当前视图」轻提示 (UI)**                                       | ❌    | **BUILD** — autoSave is silent; add a quiet token toast/hint                                                      |
| 行选择 + 本页全选(半选态)                                                 | ✅    | exists + T9 cross-page (#745)                                                                                     |
| **深色批量操作栏**                                                        | 🔶    | exists + dark, but hardcoded `bg-gray-700`/`bg-blue-500` → token-ify                                              |
| 行内编辑 (editable 双击)                                                  | ✅    | exists                                                                                                            |
| 行操作 N平铺 + ⋮                                                          | ✅    | exists                                                                                                            |
| **状态 = 色点 + 文字 (非 pill)** §1.3                                     | ❌    | **BUILD** — `status`/`tag` cell renderers use `rounded-full` pills; convert to dot+text w/ semantic status tokens |
| 条件格式                                                                  | 🔶    | type+plumbing (`conditionalFormats`) exist; verify cells apply it                                                 |
| 行高 4 档 / 21 valueType / 三态 (空·加载·错误)                            | ✅    | exists                                                                                                            |
| **CellRendererRegistry token-ify** (not gated)                            | 🔶    | `bg-gray-200`/`bg-${c}-600`/`text-gray-*` → tokens                                                                |

## §4 — form (`FormPageContent` / `FormDialog`)

| Action point                              | State | Action                                                           |
| ----------------------------------------- | ----- | ---------------------------------------------------------------- |
| 整页 vs 弹窗 共用契约                     | ✅    | exists                                                           |
| 12 字段类型→控件                          | ✅    | exists (golden-confirmed typed inputs)                           |
| form-section + 栅格                       | ✅    | exists                                                           |
| 校验 混合时机 + 字段级红字 + 顶部汇总     | ✅    | ValidationSummary + field errors (golden-confirmed "请填写名称") |
| **校验 提交时滚动定位首错**               | 🔶    | verify `scrollIntoView` to first error; build if missing         |
| 条件字段 visibleWhen / 默认值 / ?dv.      | ✅    | exists                                                           |
| reference 选择器 (搜索 + 头像 + 远程分页) | 🔶    | RelationField exists; verify search+avatar+paging conform §4     |
| 子表明细 (增/删/行内改/拖序/聚合合计)     | 🔶    | exists; verify aggregation 合计                                  |
| 上传                                      | ✅    | T7 (#708)                                                        |

## §5 — detail (`DetailPageContent`)

| Action point                                                                                                                                  | State                 | Action                                                                                         |
| --------------------------------------------------------------------------------------------------------------------------------------------- | --------------------- | ---------------------------------------------------------------------------------------------- |
| 面包屑 + 标题 + 状态标签 + 状态流转工具栏 + 右侧关键信息 sticky                                                                               | ✅                    | golden-confirmed (toolbar 激活/归档)                                                           |
| blocks: detail-section / tabs / sub-table(带合计) / embedded-list / activity-timeline / field-history / record-comments / toolbar / bpm-panel | ✅(exist) 🔶(conform) | all block files exist; **sub-table 合计页脚** + timeline inner content need conformance golden |
| 状态横幅 + state_transition (按状态显隐, 命令后刷新)                                                                                          | ✅                    | golden-confirmed                                                                               |
| 关联子列表 (API / resolveVia / FK)                                                                                                            | ✅                    | exists                                                                                         |

## This pass — build order (TDD + real-page golden each)

1. **§3 status/tag → 色点+文字** + token-ify CellRendererRegistry (highest-value, clear spec deviation).
2. **§3 「已保存到当前视图」hint** (missing UI).
3. **§3 batch bar token-ify** + confirm dark per spec.
4. **§3 conditional-format** apply-in-cell verify/complete.
5. **§4/§5 conformance golden** of reference-picker / first-error-scroll / sub-table 合计 / timeline; fix concrete gaps + token-ify non-gated renderer palette found.
