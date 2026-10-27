import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { BlockConfig } from '~/framework/meta/schemas/types';

/** Row commands read their target model, which may differ from the containing page. */
export function resolveRowActionModel(block: BlockConfig, runtime: SchemaRuntime): string {
  const source =
    typeof block.dataSource === 'string'
      ? runtime.getDataSourceManager().getConfig?.(block.dataSource)
      : block.dataSource;
  if (source && Object.hasOwn(source, 'modelCode')) {
    if (
      typeof source.modelCode !== 'string' ||
      source.modelCode.trim() === '' ||
      source.modelCode !== source.modelCode.trim()
    ) {
      throw new Error(`Block ${block.id} has an invalid data-source modelCode`);
    }
    return source.modelCode;
  }
  const schema = runtime.getSchema();
  return (schema as any).modelCode || schema.id || '';
}
