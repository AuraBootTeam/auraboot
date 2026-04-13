// Top-level page document
export type {
  PageSchema,
  PageKind,
  PageProfile,
  PageLayout,
  StackLayout,
  GridLayout,
} from './page.js'

// Blocks
export type {
  BlockSchema,
  BlockType,
  TableBlockConfig,
  FormSectionBlockConfig,
  FiltersBlockConfig,
  ToolbarBlockConfig,
  TabsBlockConfig,
  TabSchema,
  StatCardBlockConfig,
  ChartBlockConfig,
  SubTableBlockConfig,
  CustomBlockConfig,
} from './block.js'

// Field-level (used inside form-section, filters, sub-table)
export type {
  FieldSchema,
  FieldType,
  WidgetType,
  ValidatorRef,
} from './field.js'

// Column-level (used inside table, sub-table)
export type {
  ColumnSchema,
  ColumnType,
  RendererRef,
} from './column.js'

// Actions
export type {
  ActionSchema,
  ActionType,
} from './action.js'

// Data source
export type {
  DataSourceRef,
  DataSourceType,
} from './data-source.js'

// Expression / i18n
export type { Expression, ExpressionOp, DeclarativeExpression } from './expression.js'
export type { LocalizedText } from './localized-text.js'
