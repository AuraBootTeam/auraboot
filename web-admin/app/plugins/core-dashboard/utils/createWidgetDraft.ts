import type { Widget, WidgetDefinition } from '../types';

const DEFAULT_AGGREGATE_DATA_SOURCE = {
  type: 'aggregate' as const,
  metrics: [{ field: 'id', aggregation: 'count' as const }],
};

function resolveDefaultTitle(definition: WidgetDefinition): Widget['config']['title'] {
  const title = definition.defaultConfig.title;
  if (typeof title === 'string') {
    return title.trim() ? title : definition.label;
  }
  return title || definition.label;
}

/**
 * Create the unsaved widget payload used by both click-to-add and drag-to-drop.
 */
export function createWidgetDraft(
  definition: WidgetDefinition,
  position: Pick<Widget, 'x' | 'y'>,
): Omit<Widget, 'id'> {
  return {
    type: definition.type,
    componentType: definition.type,
    x: position.x,
    y: position.y,
    w: definition.defaultSize.w,
    h: definition.defaultSize.h,
    minW: definition.defaultSize.minW,
    minH: definition.defaultSize.minH,
    maxW: definition.defaultSize.maxW,
    maxH: definition.defaultSize.maxH,
    props: {},
    config: {
      ...definition.defaultConfig,
      title: resolveDefaultTitle(definition),
      dataSource: definition.defaultConfig.dataSource || DEFAULT_AGGREGATE_DATA_SOURCE,
    },
  };
}
