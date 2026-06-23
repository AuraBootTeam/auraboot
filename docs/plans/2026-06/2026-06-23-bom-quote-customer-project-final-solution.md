---
type: plan-impl
status: shipped
created: 2026-06-23
scope:
  - aura-quote
  - bom-standardization
  - crm
  - web-admin
---

# BOM 项目、报价单、客户菜单最终方案

## 结论

本轮所有“项目”统一指 BOM 项目模型 `req_requirement_set_pcba_bom`。最终业务关系固定为:

```text
客户 1 -> N BOM 项目
BOM 项目 1 -> N BOM 转换单
BOM 项目 1 -> N 报价单
```

报价单和 BOM 转换单都引用同一个 BOM 项目。项目详情只作为项目主数据详情,不再提供创建 BOM 转换入口,也不展示 BOM 转换历史或报价历史。

## 已确认口径

- 删除“物料库来源”字段在项目创建/编辑/列表/详情中的展示。
- 不保留项目详情里的转换历史查看。
- `BOM资料(必填,必须是转化过的BOM)` 是人工上传提示文案;系统只校验必填和可上传,不强校验该文件是否来自 BOM 转换任务。
- `同步金蝶物料库` 本轮作为菜单入口保留/新增;真实金蝶接口能力不在本轮扩展。
- 不改通用项目管理模型 `pm_project`。

## 菜单结构

最终可见菜单控制为 8 个:

| 层级 | 菜单 |
| --- | --- |
| 根菜单 | 报价工具 |
| 报价工具子菜单 | 报价单 |
| 报价工具子菜单 | 采购价格库 |
| 根菜单 | 客户 |
| 根菜单 | BOM转化工具 |
| BOM转化工具子菜单 | BOM 工作台 |
| 根菜单 | 项目 |
| 根菜单 | 同步金蝶物料库 |

菜单改名与移除:

| 原菜单 | 最终处理 |
| --- | --- |
| 报价中心 | 改为 `报价工具` |
| BOM 标准化 2.0 | 改为 `BOM转化工具` |
| 项目与版本 | 改为 `项目`,指向 `req_requirement_set_pcba_bom` |
| BOM 评审队列 | 隐藏/移除 |
| 物料主数据 | 隐藏/移除 |
| 结构 BOM | 隐藏/移除 |

## BOM 项目

模型: `req_requirement_set_pcba_bom`。

项目表单字段顺序和规则:

| 字段 | 规则 |
| --- | --- |
| 项目名称 | 第一字段,必填 |
| 客户 | 必填,引用客户 |
| 质量等级 | 必填,默认 `工规` |
| PCBA 编码 | 选填 |
| 备注 | 保留 |

质量等级选项:

| 值 | 展示 |
| --- | --- |
| `commercial` | 商规 |
| `industrial` | 工规 |
| `automotive` | 车规 |
| `military` | 军规 |

项目页面约束:

- 创建/编辑不展示 `产品名称`、`物料库来源`。
- 列表展示项目、客户、质量等级、PCBA 编码、更新时间等主数据字段。
- 详情只展示项目基础信息。
- 详情不展示创建 BOM 转换入口、转换历史、报价历史。

## BOM 转换单

BOM 转换单保持独立业务单据,通过客户和 BOM 项目建立归属。

创建流程:

1. 先选择客户。
2. 再选择项目。
3. 项目下拉按所选客户过滤。
4. 上传 BOM 文件并创建转换任务。

转换记录只在 BOM 转化工具相关页面查看和处理,不从项目详情承接。

## 报价单

报价单保持独立业务单据,必须引用客户和 BOM 项目。

创建流程:

1. 先选择客户。
2. 再选择项目。
3. 项目下拉按所选客户过滤。
4. 上传 `PCB资料(Gerber)` 和 `BOM资料(必填,必须是转化过的BOM)` 等资料。
5. 保存报价单。

报价单字段和文案:

| 原文案 | 最终文案 | 规则 |
| --- | --- | --- |
| Gerber/PCB资料包 | PCB资料(Gerber) | 沿用现有上传规则 |
| 修正BOM | BOM资料(必填,必须是转化过的BOM) | 必填上传,人工保证来源 |

报价单列表:

- 新增展示 `项目`。
- 新增展示 `报价修改日期`。
- 隐藏 `状态`。

后端规则:

- 创建报价单必须传客户、项目、BOM 资料。
- 项目必须归属所选客户。
- BOM 资料只做必填和上传值存储/导入处理,不做转换任务来源校验。

## 客户列表

客户菜单独立为根菜单。

客户列表隐藏:

- 电话
- 评级
- 健康分
- 健康等级

客户列表保留负责人,但负责人展示名称,不展示 pid。

## 平台前端联动能力

报价单和 BOM 转换单都依赖“先客户、后项目”的动态下拉。平台 `Select` 已补充打开下拉时触发异步数据源 `refetch()` 的能力,用于支持 `autoFetch=false` 且依赖表单值的项目下拉。

此补丁属于平台表单组件增强,不是新增业务 React 页面。

## 实现映射

主要实现范围:

| 范围 | 内容 |
| --- | --- |
| `plugins/bom-standardization/config` | BOM 项目字段、质量等级字典、菜单、页面、命令、客户/项目联动 |
| `plugins/crm/config` | 客户根菜单、客户列表列隐藏、负责人显示名称 |
| `aura-quote/plugin-aura/quote-core/config` | 报价工具菜单、报价单项目字段、列表列、上传文案、命令输入 |
| `aura-quote/plugin-aura/quote-engine/backend` | 报价创建客户/项目/BOM 必填、项目客户匹配、BOM 上传值存储 |
| `aura-quote/scripts` | Quote/BOM focused runtime 菜单 allowlist 和工费种子兼容 |
| `auraboot/web-admin` | Select 异步下拉打开时 refetch、目标 E2E 调整 |

## SOT Updates

- `auraboot-enterprise/docs/system-reference/pcba/quote-bom-customer-project-contract.md` §1-8: 已沉淀本轮客户、BOM 项目、BOM 转换单、报价单、菜单、字段文案、客户列表和前端联动的稳定契约。

## 验收清单

- 菜单只暴露最终 8 项,旧菜单不可见。
- `客户`、`项目`、`同步金蝶物料库` 为根菜单。
- 新建项目第一字段是项目名称。
- 项目客户必填,质量等级必填且默认工规。
- PCBA 编码选填。
- 项目页面不展示产品名称、物料库来源。
- 项目详情不展示 BOM 转换入口和历史。
- 新建 BOM 转换单时客户和项目联动。
- 新建报价单时客户和项目联动。
- 报价单保存后引用客户和项目。
- BOM 资料必填上传,文案为 `BOM资料(必填,必须是转化过的BOM)`。
- 报价单列表展示项目和报价修改日期,不展示状态。
- 客户列表不展示电话、评级、健康分、健康等级。
- 客户负责人展示名称,不展示 pid。

## 本轮验证记录

已执行的关键验证:

| 验证 | 结果 |
| --- | --- |
| Select 单测 | `7 passed` |
| 前端类型检查 | `pnpm typecheck` 通过 |
| QuoteCore 配置测试 | `34 passed` |
| BOM/CRM 配置与 host 合同测试 | `57 passed` |
| Quote/BOM focused menu 脚本测试 | `27 passed` |
| QuoteEngine handler 测试 | `BUILD SUCCESSFUL` |
| Quote/BOM golden runtime | backend/BFF/Web healthy; focused menu `current_visible=8`; reference integrity valid |
| PF4J handler 注册 | `bom_handlers=13`, `crm_handlers=16`, `quote_handlers=39` |
| 工费规则种子 | published active rule set `1`, rule lines `10` |
| 目标 E2E | `25 passed`, `1 skipped` |
| E2E 真实性审计 | 无 `skip/fixme`、无 `waitForTimeout`、无直达 `/p/...` 业务页;保留 1 个 Excel 导出行数业务下限断言 |

目标 E2E 覆盖:

- BOM 工作台黄金路径。
- QuoteOps + BOM focused menu 和权限矩阵。
- 报价单从客户、联动项目、BOM 资料上传创建的最小回归路径。
