# Meta Framework

AuraBoot Low-Code/No-Code 平台核心框架

## 目录结构

```
app/meta/
├── schemas/                 # DSL Schema 定义层
│   └── types.ts            # 统一类型定义
│
├── runtime/                # 运行时系统层
│   ├── expression/         # 表达式系统
│   ├── state/              # 状态管理
│   ├── data-pipeline/      # 数据管道
│   ├── events/             # 事件系统
│   ├── theme/              # 主题系统
│   └── schema-runtime.ts   # 统一 Runtime
│
├── hooks/                  # React Hooks
│   ├── useSchemaLoader.ts
│   └── useDataSourceManager.ts
│
└── index.ts               # 统一导出
```

## 快速开始

### 1. 加载 Schema

```typescript
import { useSchemaLoader } from '~/meta';

const { schema, loading, error } = useSchemaLoader({
  tableName: 'store',
  type: 'list',
  token
});
```

### 2. 创建 Runtime

```typescript
import { SchemaRuntime } from '~/meta';

const runtime = new SchemaRuntime({
  schema,
  globalState: {
    user: { permissions: ['store:create'] },
    locale: 'zh-CN'
  }
});
```

### 3. 执行 Handler

```typescript
// 执行按钮点击
await runtime.executeHandler('deleteSelected', { id: '123' });
```

### 4. 管理数据源

```typescript
import { usePageDataSources } from '~/meta/hooks/usePageDataSources';
import { DataSourceProvider, useDataSourceManager } from '~/meta/contexts/DataSourceContext';

// 在页面级别创建 DataSourceManager
const { manager, getData, fetch, reload } = usePageDataSources({
  context: baseExpressionContext,
  dataSources: schema?.dataSources,
});

// 通过 Provider 共享给子组件
return (
  <DataSourceProvider manager={manager}>
    {/* 子组件可以通过 useDataSourceManager 访问 */}
    <MyForm />
  </DataSourceProvider>
);

// 在子组件中访问
function MyForm() {
  const manager = useDataSourceManager(); // 从 Context 获取
  const listData = manager.getData('ds_storeList');
  
  // 刷新数据源
  await manager.reload(['ds_storeList', 'ds_storeStats']);
}
```

## 表达式系统

### 支持的语法

#### 计算表达式 `${}`

```json
{
  "visibleWhen": "${hasPermission('store:create')}",
  "label": "${row.type === 'DIRECT' ? row.manager : '---'}"
}
```

#### 数据绑定 `{{}}`

```json
{
  "params": "{{state.filters}}",
  "model": "{{state.form}}"
}
```

### 内置函数

- `hasPermission(permission)` - 权限判断
- `formatDate(date, format)` - 日期格式化
- `formatCurrency(value, currency)` - 货币格式化
- `t(key, vars)` - 国际化

## i18n 支持

### $i18n: 简写

```json
{
  "content": "$i18n:form.store.submit.success"
}
```

### ICU MessageFormat

```json
{
  "zh-CN": {
    "msg.unread": "{name},你有 {count, plural, =0 {没有} other {#条}} 未读消息"
  }
}
```

## Design Tokens

### DSL 中使用

```json
{
  "style": {
    "color": "$color.text.secondary",
    "fontSize": "$font.size.sm",
    "padding": "$spacing.md"
  }
}
```

### 运行时解析

```typescript
import { resolveToken, resolveStyleTokens } from '~/meta';

resolveToken('$color.text.primary')
// => 'var(--text-primary, #1f2937)'

resolveStyleTokens({
  color: '$color.text.primary',
  fontSize: '$font.size.base'
})
// => { color: 'var(--text-primary)', fontSize: '16px' }
```

## 内置 Handlers

### 使用内置 Handler

```json
{
  "handlers": {
    "submitForm": {
      "type": "builtin.formSubmit",
      "formRef": "form.store",
      "api": "/api/stores",
      "reload": ["ds_storeList"]
    }
  }
}
```

### 默认按钮行为

```json
{
  "buttons": [
    { "code": "create" }  // 自动使用 builtin.navigateToCreate
  ]
}
```

### 条件分支

```json
{
  "handlers": {
    "deleteSelected": {
      "type": "flow",
      "steps": [
        {
          "type": "if",
          "condition": "${state.selectedIds.length > 0}",
          "trueNext": "confirmDelete",
          "falseNext": "noSelection"
        },
        { "id": "confirmDelete", "action": "dialog.confirm" },
        { "id": "noSelection", "action": "noop" }
      ]
    }
  }
}
```

## 数据源

### API 数据源

```json
{
  "ds_storeList": {
    "type": "api",
    "endpoint": "/api/stores",
    "params": "{{state.filters}}",
    "autoFetch": true,
    "adaptor": "table"
  }
}
```

### 字典数据源

```json
{
  "ds_storeTypes": {
    "type": "dict",
    "dictKey": "store.types"
  }
}
```

### 静态数据源

```json
{
  "ds_options": {
    "type": "static",
    "data": [
      { "value": "1", "label": "选项1" }
    ]
  }
}
```

## 状态管理

### 作用域隔离

```typescript
import { ScopedStateManager } from '~/meta';

const manager = new ScopedStateManager(globalState);

// 创建独立作用域
manager.createScope('form.store');
manager.createScope('form.user');

// 更新表单
manager.updateForm('form.store', 'name', '店铺A');
```

### 订阅变化

```typescript
const unsubscribe = manager.subscribe(
  'form.store',
  (state) => state.form,
  (formData) => console.log('Form changed:', formData)
);
```

## 更多文档

- [重构实施进度报告](../../docs/重构实施进度报告.md)
- [综合重构方案分析与建议](../../docs/综合重构方案分析与建议.md)
