---
type: backlog
status: active
created: 2026-06-19
owner: diqi
---

# 标准 DSL 页面"约定大于配置"的命令路由

> 范围:OSS `auraboot` 平台(web-admin + platform)。目标:标准新建/编辑/删除页**零命令配置**、URL 不再出现 `?commandCode=`,命令由约定从`模型 + 操作类型 + 模式`解析;非标准页仍可显式 override。

## 一、来龙去脉

1. **触发点**:`/p/showcase_all_fields/new?commandCode=sc%3Acreate_showcase` 上「编号」只读自动生成却标必填 `*`。→ 显示层缺 `!readOnly` 守卫,已修(PR #840 → `0a6c7239d`)。
2. **暴露的问题**:创建页 URL 带 `?commandCode=sc%3Acreate_showcase`,URL 编码冒号 + 内部命令码进地址栏,命令驱动模型的新建页普遍如此。
3. **设计主张(owner)**:约定大于配置——标准 DSL 页每个 button 的 action 应由约定确定,标准页零配置,非标准页才显式配。

## 二、问题定义

**症状**:命令驱动模型的新建/编辑页按钮硬编码 `command`,被序列化进 URL(`?commandCode=`),丑、泄漏实现、且每页重复配置。

**根因(机制链)**:
- `<model>_form` 是 create/edit 共用的通用渲染器,自身不绑定命令。
- 命令由入口按钮决定:列表「新建」按钮配 `action:{type:navigate,to:<form>,command:'sc:create_showcase'}`(实测 `showcase_all_fields_list` toolbar)。
- `navigate` 处理器把 `action.command` 拼进 URL(`web-admin/app/framework/meta/hooks/useActionHandler.ts:684-709`)。
- 表单页读 `urlCommandCode`(`FormPageContent.tsx:672`)→ 提交走该命令(`:1394`);无 commandCode 时回退裸 CRUD(`:1459`)。

**为什么不能直接删**:命令驱动模型的 create 必须走业务命令(showcase:编号自动生成 + `sc_name` 唯一校验,见 `execution_config.validation`),裸 CRUD 跑不了。**问题在投递方式(URL 硬配),不在信息本身**。

## 三、现状盘点(约定机制已建好约 80%,只是没接通)

| # | 已有能力 | 证据 |
|---|---|---|
| 1 | 前端标准 action 约定层:按钮 `code`→标准动作(`create→new`、`submit→save`),已注册 `new/edit/view/save/delete/...` | `ActionRegistry.ts:233`;`executeRegistryAction.ts:75` |
| 2 | 后端按模型解析 create 命令的约定逻辑 | `TestFixtureController.resolveCreateCommandCode():426` + `commandDefinitionMapper.findByModelCode()` |
| 3 | 命令完全自描述:`model_code` + `execution_config.type`(create/update/delete/query/state_transition) | `ab_command_definition`;showcase 8 命令 type 实测齐全 |

**缺口**:
- 前端约定动作 `new` 只 `navigate('/p/<model>/new')`,不解析命令;表单页拿不到模型命令,只能靠 URL `commandCode`。
- 后端约定解析器只服务测试夹具(`TestFixtureController`),未暴露给页面运行时。
- → 命令驱动模型退化成"每页显式配 command + 丑 URL"。

## 四、设计原则

**标准走约定,特殊走配置**(均有 escape hatch)。
- 标准页零配置:有规范 create/update/delete 命令的模型 → 按钮只需 `code`,命令由`模型 + type + 模式(有无 recordId)`解析;URL 无 `commandCode`。
- 非标准页:按钮显式 `action.command`(自定义/向导/状态流转)→ 现有方式工作,作为 override。
- 解析基准:用 `execution_config.type` 精确匹配,**不用** `code.contains("create")` 子串(`recreate`/`create_draft` 会误判)。

## 五、目标方案(架构)

核心:把"模型 → typed 命令映射"放进页面已经会拉取的元数据,运行时按约定消费。

1. **后端**:在 page-schema / field-meta(同一次请求,不新增往返)返回该模型命令映射:
   ```json
   "commands": { "create": "sc:create_showcase", "update": "sc:update_showcase", "delete": "sc:delete_showcase" }
   ```
   来源 = `findByModelCode(modelCode)` 按 `execution_config.type` 解析(把 `TestFixtureController` 逻辑提升为正式 service,改用 type 精确匹配)。type 缺失 → 该键缺省。
2. **前端约定动作**消费:`new`/`save` 按模式选 `commands.create`(无 recordId)/`commands.update`(有 recordId);`delete` 走 `commands.delete`;走命令引擎。映射无对应命令 → 维持裸 CRUD 回退,不受影响。
3. **默认页生成器** `autoCreateDefaultPages`:标准 create/edit/delete 按钮不再写 `command`。
4. **override 保留**:显式 `action.command` 优先级最高;`?commandCode=` URL 参数继续识别(向后兼容)。

效果:`/p/showcase_all_fields/new` 干净 URL;create→`sc:create_showcase`、edit→`sc:update_showcase` 由"有无 recordId"自动分流;表单/按钮零命令配置。

## 六、改造点清单

| 层 | 改动 | 位置 |
|---|---|---|
| 后端 | 命令解析提升为正式 service,按 `execution_config.type` 解析 typed 命令映射;在 page-schema/field-meta 响应返回 | `commandDefinitionMapper.findByModelCode`(已存在)+ 新 service;page/field-meta controller;`TestFixtureController:426` 改用 type |
| 前端 | 约定动作 `new`/`save`/`delete` 用 `commands` 映射按模式路由 | `ActionRegistry.ts`、`executeRegistryAction.ts` |
| 前端 | 表单页提交按模式解析命令,URL 参数降级为 override | `FormPageContent.tsx`(`urlCommandCode`/`effectiveCommandCode`/`inferEditCommandCode`) |
| 生成器 | 默认页标准按钮停止内嵌 `command` | `autoCreateDefaultPages`(platform) |
| 数据 | 重新 seed 默认页 schema(去冗余 command、清 URL);存量页兼容 | reset/init + import |
| 测试 | 单测(解析 by type)+ 真浏览器 golden(URL 干净、create/edit/delete 正确路由) | 新增 |

## 七、兼容与迁移

- 存量带 `commandCode` 的页面/直达链接:URL 参数仍识别 → 不破坏。
- 纯 CRUD 模型:`commands` 映射为空 → 走现有 CRUD 回退,行为不变。
- 重新 seed 只为清冗余配置 + 干净 URL,非正确性前提(约定优先 + 显式 override 仍在)。

## 八、风险

1. 平台级爆炸半径:影响所有命令驱动模型的新建/编辑/删除 + 默认页生成 + reset/seed,须全量 golden 回归。
2. 命令解析歧义:一个模型多个 `type=create`(create + create_draft)→ 约定无法唯一确定 → 退回显式配置(或引入 `primary` 标记)。
3. 覆盖范围:约定只覆盖 create/update/delete;`state_transition`/query/自定义命令仍走显式配置。
4. 重新 seed 回归:改生成器后存量默认页重生成,可能触发 page-golden/validator;先 infra 可达 + 真浏览器 golden。
5. 跨仓:OSS 改生成器/元数据契约,enterprise overlay 默认页/插件须一并验证(boundary + 全量 import)。

## 九、落地节奏

- **Phase 0 — showcase 切片**:后端返回 typed 命令映射 + 前端约定消费 + 去掉 showcase 默认页按钮 command。真浏览器 golden 验:`/p/showcase_all_fields/new` 干净 URL、create/edit/delete 正确路由、纯 CRUD 不回归。
- **Phase 1 — 推广**:`autoCreateDefaultPages` 改造 + 全量 re-seed + 全量页面 golden + enterprise overlay 验证。
- **Phase 2 — 收尾**:存量页清理 command 配置;文档(DSL 能力参考 + 按钮 action 约定)更新;`?commandCode=` 标记为 override-only。

## 十、验收标准

- 标准新建/编辑/删除页按钮无 command 配置、URL 无 `commandCode`;create/edit/delete 仍正确走业务命令(真浏览器 golden + DB 反查)。
- 纯 CRUD 模型不变;显式 override 不变;存量直达链接不破。
- 单测(解析 by type)+ 全量 golden 绿;enterprise 全量 import success。
