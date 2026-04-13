/**
 * Meta Framework - 统一导出
 * Low-Code/No-Code 平台核心框架
 */

// ============ Schemas & Types ============
export * from '~/framework/meta/schemas/types';

// ============ Runtime ============
export { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
export type { SchemaRuntimeConfig } from '~/framework/meta/runtime/schema-runtime';

// Expression
export * from '~/framework/meta/runtime/expression/context';
export * from '~/framework/meta/runtime/expression/evaluator';
export * from '~/framework/meta/runtime/expression/i18n-renderer';

// State
export * from '~/framework/meta/runtime/state/scoped-state';

// Data Pipeline
export { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
export type { DataSourceState } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';

// Events
export * from '~/framework/meta/runtime/events/builtin-handlers';

// Actions
export { actionRegistry } from '~/framework/meta/runtime/actions/ActionRegistry';
export type { ActionContext, ActionHandler } from '~/framework/meta/runtime/actions/ActionRegistry';

// Renderers
export { cellRendererRegistry } from '~/framework/meta/runtime/renderers/CellRendererRegistry';
export type {
  CellRendererContext,
  CellRenderer,
} from '~/framework/meta/runtime/renderers/CellRendererRegistry';

// Theme
export * from '~/framework/meta/runtime/theme/tokens';

// ============ Hooks ============
export * from '~/framework/meta/hooks/useSchemaLoader';
export * from '~/framework/meta/hooks/usePageDataSources';
export * from '~/framework/meta/hooks/useFieldDataSource';
