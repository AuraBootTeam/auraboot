/**
 * Meta Framework - 统一导出
 * Low-Code/No-Code 平台核心框架
 */

// ============ Schemas & Types ============
export * from '~/meta/schemas/types';

// ============ Runtime ============
export { SchemaRuntime } from '~/meta/runtime/schema-runtime';
export type { SchemaRuntimeConfig } from '~/meta/runtime/schema-runtime';

// Expression
export * from '~/meta/runtime/expression/context';
export * from '~/meta/runtime/expression/evaluator';
export * from '~/meta/runtime/expression/i18n-renderer';

// State
export * from '~/meta/runtime/state/scoped-state';

// Data Pipeline
export { DataSourceManager } from '~/meta/runtime/data-pipeline/DataSourceManager';
export type { DataSourceState } from '~/meta/runtime/data-pipeline/DataSourceManager';

// Events
export * from '~/meta/runtime/events/builtin-handlers';

// Actions
export { actionRegistry } from '~/meta/runtime/actions/ActionRegistry';
export type { ActionContext, ActionHandler } from '~/meta/runtime/actions/ActionRegistry';

// Renderers
export { cellRendererRegistry } from '~/meta/runtime/renderers/CellRendererRegistry';
export type {
  CellRendererContext,
  CellRenderer,
} from '~/meta/runtime/renderers/CellRendererRegistry';

// Theme
export * from '~/meta/runtime/theme/tokens';

// ============ Hooks ============
export * from '~/meta/hooks/useSchemaLoader';
export * from '~/meta/hooks/usePageDataSources';
export * from '~/meta/hooks/useFieldDataSource';
