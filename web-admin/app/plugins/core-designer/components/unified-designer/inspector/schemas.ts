import type { PropertySchema } from '~/shared/designer';
import type { DslBlockV3 } from '../types';
import { defaultInspectorSchemaRegistry } from '../registry/InspectorSchemaRegistry';

export function getInspectorFields(block: DslBlockV3 | null): PropertySchema<string>[] {
  if (!block) return [];
  return defaultInspectorSchemaRegistry.getFieldsForBlock(block);
}
