import { useState, useEffect } from 'react';
import { useDraggable } from '@dnd-kit/core';
import { DRAG_TYPES } from '~/studio/workbench/constants';
import type { MetaFieldDTO } from '~/studio/workbench/panels/fields/types';
import { DATA_TYPE_COMPONENT_MAP } from '~/studio/workbench/panels/fields/types';

/**
 * Resolves the Smart Component configuration for a given field.
 */
function resolveComponentConfig(field: MetaFieldDTO) {
  const mapping = DATA_TYPE_COMPONENT_MAP[field.dataType] || DATA_TYPE_COMPONENT_MAP.STRING;
  const isReadonly = field.virtualType === 'computed_readonly';

  return {
    type: mapping.type,
    name: field.displayName || field.code,
    props: {
      label: field.displayName || field.code,
      name: field.code,
      placeholder: `${field.displayName || field.code}`,
      required: field.required || false,
      disabled: isReadonly,
      readonly: isReadonly,
      ...mapping.defaultProps,
      // Attach field metadata for property panel
      _fieldMeta: {
        pid: field.pid,
        code: field.code,
        dataType: field.dataType,
        virtualType: field.virtualType,
        computeExpression: field.computeExpression,
      },
    },
    span: 1,
  };
}

interface UseFieldDragOptions {
  field: MetaFieldDTO;
}

/**
 * Hook for making a field item draggable.
 * Reuses PALETTE_ITEM drag type so existing drop handler creates the component.
 *
 * @since 3.1.0
 */
export function useFieldDrag({ field }: UseFieldDragOptions) {
  const [isClient, setIsClient] = useState(false);

  useEffect(() => {
    setIsClient(true);
  }, []);

  const componentConfig = resolveComponentConfig(field);

  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: `field-${field.pid}`,
    data: {
      type: DRAG_TYPES.PALETTE_ITEM,
      component: componentConfig,
    },
    disabled: !isClient,
  });

  const style = transform
    ? {
        transform: `translate3d(${transform.x}px, ${transform.y}px, 0)`,
        zIndex: 1000,
      }
    : undefined;

  return {
    attributes: isClient ? attributes : {},
    listeners: isClient ? listeners : {},
    setNodeRef,
    style,
    isDragging,
    isClient,
  };
}
