/**
 * Flow PropertyField - Thin wrapper around shared PropertyFieldRenderer.
 * Resolves I18nText labels via useSmartText before delegating to the
 * unified renderer.
 */

import React, { useMemo } from 'react';
import { useSmartText } from '~/utils/i18n';
import { useFlowFieldAdapter } from '../adapters/FlowFieldAdapter';
import { PropertyFieldRenderer } from '~/shared/designer';
import type { PropertySchema as SharedPropertySchema } from '~/shared/designer';
import type { PropertySchema } from '../nodes/types';

export interface PropertyFieldProps {
  schema: PropertySchema;
  nodeId: string;
}

export function PropertyField({ schema, nodeId }: PropertyFieldProps) {
  const st = useSmartText();

  const adapter = useFlowFieldAdapter({
    fieldKey: schema.key,
    nodeId,
    required: schema.required,
  });

  // Resolve I18nText labels to plain strings for the shared renderer.
  // Destructure to exclude array-specific fields (itemSchema/itemLabel/addButtonLabel)
  // which carry I18nText generics incompatible with PropertySchema<string>.
  const resolvedSchema = useMemo((): SharedPropertySchema<string> => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { itemSchema: _is, itemLabel: _il, addButtonLabel: _ab, ...rest } = schema as any;
    return {
      ...rest,
      label: st(schema.label),
      placeholder: schema.placeholder ? st(schema.placeholder) : undefined,
      description: schema.description ? st(schema.description) : undefined,
      options: schema.options?.map((opt) => ({
        label: st(opt.label),
        value: opt.value,
      })),
      group: schema.group != null ? st(schema.group) : undefined,
    };
  }, [schema, st]);

  return <PropertyFieldRenderer schema={resolvedSchema} adapter={adapter} />;
}

export default PropertyField;
