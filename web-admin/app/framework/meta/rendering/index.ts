/**
 * Meta Rendering - 统一导出
 */

// Main renderers
export { SchemaRenderer, SchemaRendererWithContainer } from '~/framework/meta/rendering/SchemaRenderer';
export { BlockRenderer } from '~/framework/meta/rendering/BlockRenderer';
export { FieldRenderer } from '~/framework/meta/rendering/FieldRenderer';

// Block renderers
export { FormBlockRenderer } from '~/framework/meta/rendering/blocks/FormBlockRenderer';
export { TableBlockRenderer } from '~/framework/meta/rendering/blocks/TableBlockRenderer';
export { FiltersBlockRenderer } from '~/framework/meta/rendering/blocks/FiltersBlockRenderer';
export { ToolbarBlockRenderer } from '~/framework/meta/rendering/blocks/ToolbarBlockRenderer';
export { DescriptionBlockRenderer } from '~/framework/meta/rendering/blocks/DescriptionBlockRenderer';
export { ChartBlockRenderer } from '~/framework/meta/rendering/blocks/ChartBlockRenderer';
export { TabsBlockRenderer } from '~/framework/meta/rendering/blocks/TabsBlockRenderer';

// Utils
export {
  deriveTestId,
  listTestId,
  formTestId,
  fieldTestId,
  buttonTestId,
  tabTestId,
  detailTestId,
  rowTestId,
  colTestId,
  actionTestId,
} from '~/framework/meta/rendering/utils/deriveTestId';

// Types
export type { SchemaRendererProps } from '~/framework/meta/rendering/SchemaRenderer';
export type { BlockRendererProps } from '~/framework/meta/rendering/BlockRenderer';
export type { FieldRendererProps } from '~/framework/meta/rendering/FieldRenderer';
