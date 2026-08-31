---
type: system-reference
status: active
---

# 列表 URL 与 SavedView 状态契约

动态列表的排序状态有三个来源：用户交互、初始 URL 参数和当前 SavedView。运行时按以下优先级解析：

1. 用户在列表上做出的排序变更。
2. 页面 mount 时 URL 中的显式 `sort` 参数。
3. 当前 SavedView 的 `viewConfig.sorts`。
4. DSL table block 的默认排序。

SavedView 加载属于 hydration，不属于用户排序变更。因此：

- 打开干净的列表 URL 时，默认 SavedView 的排序正常生效，但运行时不得把该默认值回写成 `sort` 参数。
- 打开 `?sort=<field>:<direction>` 时，该排序会覆盖 SavedView 基线，后续 SavedView hydration 不得改写它。
- 显式选择 SavedView 后，该视图成为新的基线；用户再次修改排序时，才需要写入 `sort`。
- 用户从默认状态修改排序、或从 URL 深链清除排序时，运行时同步 URL，使刷新和前进/后退能恢复相同结果。

URL projection 必须继续复用 serialized search-param writer 和离开列表后的 write gate，
避免 SavedView、筛选、分页并发更新互相覆盖，或列表卸载后的旧 effect 污染目标路由。
