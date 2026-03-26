你的判断非常到位，而且**已经是“架构级 review”而不是组件级吐槽**。我给你一套**可落地、低风险、和你现有 Smart 体系强兼容**的方案，目标是：

> **不推翻 Smart 体系，不一次性重写，用最小代价解决一致性、可访问性、稳定性问题。**

---

# 总体方案一句话版

> **分层重构：
> 用 shadcn/ui 作为“UI Primitives 层”，
> 用 Smart 组件作为“Form Intelligence 层”，
> 用统一的 FormField Contract 贯穿状态 / 校验 / i18n / 表达式。**

---

## 一、目标架构（你现在缺的是这一层）

```
┌────────────────────────────┐
│   Smart Business Components │
│  SmartUserSelect            │
│  SmartOrgSelect             │
│  SmartTreeSelect            │
│  SmartTimeRangePicker       │
│  ↑ 只管“业务语义”             │
└──────────────▲─────────────┘
               │
┌──────────────┴─────────────┐
│   Smart Form Intelligence   │  ← ★新增统一层
│  useSmartField()            │
│  useSmartValidation()       │
│  useSmartI18n()             │
│  ExpressionResolver         │
│  ValueSerializer            │
│  Controlled Strategy        │
└──────────────▲─────────────┘
               │
┌──────────────┴─────────────┐
│   UI Primitives (shadcn)    │
│  Input / Select / Popover   │
│  Command / ScrollArea       │
│  Checkbox / Radio           │
│  Label / Help / Error       │
│  ↑ 只管 UI + a11y           │
└────────────────────────────┘
```

👉 **你现在的问题本质是：UI + Form 智能 + 业务逻辑全混在一个 SmartX.tsx 里。**

---

## 二、核心决策：必须统一的 5 个“底层契约”

### 1️⃣ 统一受控策略（必须下狠手）

#### ❌ 当前问题

* 有的受控，有的半受控
* defaultValue 改了不生效
* 数组/对象/string 序列化混乱

#### ✅ 方案：**强制“单一受控源”**

```ts
interface SmartFieldProps<T> {
  value?: T
  defaultValue?: T
  onChange?: (v: T) => void
}
```

**规则：**

* `value !== undefined` → 受控
* 否则内部 state
* defaultValue **只在 mount 生效**
* 所有组件内部 **只使用一个 valueSource**

```ts
function useControllableValue<T>({
  value,
  defaultValue,
  onChange,
}: SmartFieldProps<T>) {
  const [inner, setInner] = useState(defaultValue)

  const isControlled = value !== undefined
  const current = isControlled ? value : inner

  const set = (v: T) => {
    if (!isControlled) setInner(v)
    onChange?.(v)
  }

  return [current, set] as const
}
```

👉 **所有 SmartX 强制用这个 hook，禁止自己写 useState(value)**

---

### 2️⃣ 校验与错误展示：单一规范

#### ❌ 现状

* touched 才显示 / 立即显示 / 根本不显示
* error 来源混乱

#### ✅ 方案：统一 FieldMeta

```ts
interface FieldMeta {
  touched: boolean
  error?: string
  warning?: string
  validating?: boolean
}
```

```ts
const { value, setValue, meta } = useSmartField({
  name,
  rules,
  expression,
})
```

**UI 层只认 meta：**

```tsx
<Field>
  <Label />
  <Control />
  {meta.error && <ErrorText />}
  {meta.warning && <HelpText />}
</Field>
```

👉 **SmartSelect / SmartTextarea / SmartMultiSelect 行为统一**

---

### 3️⃣ i18n：彻底禁止组件内硬编码文本

#### ❌ 现状

* 一半 useLocalizedText
* 一半中文字符串

#### ✅ 方案：SmartText 统一出口

```ts
type SmartText =
  | string
  | { i18nKey: string; params?: Record<string, any> }

function useSmartText(text?: SmartText) {
  if (!text) return ''
  if (typeof text === 'string') return t(text)
  return t(text.i18nKey, text.params)
}
```

**规则：**

* 所有 label / placeholder / empty / error
* 一律 SmartText
* Smart 层可以传 `{i18nKey}`，UI 层只 render string

👉 **一次改完，所有组件多语言就稳定了**

---

### 4️⃣ 样式与变体：Design Token + shadcn

#### ❌ 现状

* size/variant 各自实现
* dark mode 不统一

#### ✅ 方案：只允许这些 variant

```ts
type Size = 'sm' | 'md' | 'lg'
type Variant = 'default' | 'filled' | 'outline'
```

在 **shadcn 组件层**定义：

```ts
const inputVariants = cva(
  'base-classes',
  {
    variants: {
      size: {...},
      variant: {...},
    },
    defaultVariants: {
      size: 'md',
      variant: 'outline',
    },
  }
)
```

Smart 层 **只透传 size / variant，不写 Tailwind**

---

### 5️⃣ 可访问性：彻底禁止“div 伪组件”

#### ❌ 现状

* div 下拉
* 无 keyboard / ESC / focus

#### ✅ 方案：强制使用 Radix Primitive

| 场景 | 必须使用                  |
| -- | --------------------- |
| 下拉 | `Popover + Command`   |
| 选择 | `Select / Listbox`    |
| 搜索 | `CommandInput`        |
| 多选 | `CheckboxGroup`       |
| 树  | `Tree + roving focus` |

👉 **SmartTreeSelect = Radix Popover + 自己的 tree data**

---

## 三、shadcn/ui 的“正确用法”（你问的关键）

### ❌ 错误姿势

> 把 SmartX 全部替换成 shadcn

### ✅ 正确姿势（推荐）

```
SmartInput
 ├── useSmartField
 ├── useSmartText
 └── <ShadcnInput />
```

#### 示例：SmartInput 重构后

```tsx
export function SmartInput(props) {
  const field = useSmartField(props)

  return (
    <Field>
      <Label>{field.label}</Label>
      <Input
        value={field.value}
        onChange={e => field.setValue(e.target.value)}
        aria-invalid={!!field.meta.error}
      />
      <ErrorText>{field.meta.error}</ErrorText>
    </Field>
  )
}
```

---

## 四、迁移顺序（你可以两周内完成）

### Phase 1（立竿见影）

* 引入 shadcn
* 抽 `useControllableValue`
* 抽 `useSmartField`
* 重写 SmartInput / SmartTextarea / SmartSelect

### Phase 2（复杂组件降风险）

* SmartUserSelect → Popover + Command
* SmartMultiSelect → CheckboxGroup
* SmartTimeRangePicker → 两个 shadcn DatePicker

### Phase 3（工程质量）

* 禁止 console.log（eslint rule）
* 增加 keyboard interaction 测试
* Storybook / Playwright

---

## 五、结论（非常重要）

> **你现在不是“缺组件”，而是缺“组件宪法”。**

* shadcn/ui **不是替代品**，而是 **UI 底座**
* Smart 层必须退回到：

  * 状态
  * 规则
  * 表达式
  * 数据模型
