import type { JsonValue } from './http-client/internal-api';
import type {
  SavedView,
  ViewConfig,
  ViewScope,
  ViewType,
  ConditionalFormatRule,
  SavedViewCapabilityCheckResponse,
  SavedViewCapabilityReason,
} from '~/framework/smart/types/savedView';
import type {
  PresentFields,
  SavedViewWire,
  SavedViewConfigWire,
  SavedViewCreateWire,
  SavedViewCapabilityWire,
  SavedViewReasonWire,
} from '~/framework/smart/types/savedViewWire';

/** Nullable optional DTO properties become absent display settings. JSON operands are handled separately. */
export function presentFields<T extends object>(value: T): PresentFields<T> {
  return Object.fromEntries(
    Object.entries(value).filter(([, item]) => item != null),
  ) as PresentFields<T>;
}

function text(value: unknown, label: string): string {
  if (typeof value !== 'string' || !value) throw new Error(`Saved view has an invalid ${label}`);
  return value;
}
function choice<T extends string>(value: unknown, values: readonly T[], label: string): T {
  if (!values.some((item) => item === value))
    throw new Error(`Saved view has an unsupported ${label}`);
  return value as T;
}
function optionalChoice<T extends string>(
  value: unknown,
  values: readonly T[],
  label: string,
): T | undefined {
  return value == null ? undefined : choice(value, values, label);
}
function optionalText(value: unknown, label: string): string | undefined {
  if (value == null) return undefined;
  if (typeof value !== 'string') throw new Error(`Saved view has an invalid ${label}`);
  return value;
}
function optionalBoolean(value: unknown, label: string): boolean | undefined {
  if (value == null) return undefined;
  if (typeof value !== 'boolean') throw new Error(`Saved view has an invalid ${label}`);
  return value;
}
function object(value: unknown, label: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error(`Saved view has an invalid ${label}`);
  return value as Record<string, unknown>;
}
function localized(value: unknown): string | Record<string, string> | undefined {
  if (value == null) return undefined;
  if (typeof value === 'string') return value;
  const entries = Object.entries(object(value, 'localized label'));
  if (entries.some(([, item]) => typeof item !== 'string'))
    throw new Error('Saved view has an invalid localized label');
  return Object.fromEntries(entries) as Record<string, string>;
}

/** Preserve explicit JSON null, zero and false. Date operands use the existing JSON ISO representation. */
export function savedViewJson(value: unknown, ancestors = new Set<object>()): JsonValue {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return value;
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (value instanceof Date) {
    if (!Number.isFinite(value.getTime())) throw new Error('Saved view contains an invalid date');
    return value.toISOString();
  }
  if (typeof value !== 'object' || !value) throw new Error('Saved view contains a non-JSON value');
  if (ancestors.has(value)) throw new Error('Saved view contains circular configuration');
  if (!Array.isArray(value) && ![Object.prototype, null].includes(Object.getPrototypeOf(value))) {
    throw new Error('Saved view contains a non-JSON object');
  }
  ancestors.add(value);
  try {
    if (Array.isArray(value)) return value.map((item) => savedViewJson(item, ancestors));
    return Object.fromEntries(
      Object.entries(value)
        .filter(([, item]) => item !== undefined)
        .map(([key, item]) => [key, savedViewJson(item, ancestors)]),
    );
  } finally {
    ancestors.delete(value);
  }
}

function formattingOperand(value: unknown): string | number | boolean | null | undefined {
  if (value == null || typeof value === 'string' || typeof value === 'boolean') return value;
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  throw new Error('Saved view has an invalid formatting operand');
}
function filterLogic(value: unknown): 'and' | 'OR' | undefined {
  if (value == null) return undefined;
  const normalized = typeof value === 'string' ? value.toLowerCase() : value;
  return choice(normalized, ['and', 'or'], 'filter logic') === 'and' ? 'and' : 'OR';
}

function conditionalFormat(value: Record<string, unknown>): ConditionalFormatRule {
  const style = object(value.style, 'formatting style');
  return {
    ...value,
    fieldCode: text(value.fieldCode, 'formatting field'),
    operator: choice(
      value.operator,
      ['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'like', 'isNull', 'isNotNull'],
      'formatting operator',
    ),
    value: formattingOperand(value.value),
    style: {
      ...style,
      backgroundColor: optionalText(style.backgroundColor, 'background color'),
      textColor: optionalText(style.textColor, 'text color'),
      bold: optionalBoolean(style.bold, 'bold setting'),
    },
  };
}

/** An explicit UI projection; nullable DTO members never become fabricated defaults. */
export function savedViewConfigForView(
  config: SavedViewConfigWire | null | undefined,
): ViewConfig | undefined {
  if (config == null) return undefined;
  const meta =
    config.meta == null
      ? undefined
      : {
          ...presentFields(config.meta),
          overlayStatus: optionalChoice(
            config.meta.overlayStatus,
            ['CURRENT', 'REBASED', 'STALE', 'UNTRACKED'],
            'overlay status',
          ),
          collaborators: config.meta.collaborators?.map((item) => ({
            ...presentFields(item),
            principalPid: text(item.principalPid, 'collaborator identity'),
            permission: text(item.permission, 'collaborator permission'),
          })),
        };
  return {
    ...presentFields(config),
    density: optionalChoice(config.density, ['compact', 'default', 'comfortable'], 'density'),
    rowHeight: optionalChoice(
      config.rowHeight,
      ['short', 'medium', 'tall', 'extra-tall'],
      'row height',
    ),
    galleryAspectRatio: optionalChoice(
      config.galleryAspectRatio,
      ['square', '4:3', '16:9', 'auto'],
      'image aspect ratio',
    ),
    columns: config.columns?.map((item) => ({
      ...presentFields(item),
      fieldCode: text(item.fieldCode, 'column identity'),
      frozenPosition: optionalChoice(item.frozenPosition, ['left', 'right'], 'column position'),
    })),
    sorts: config.sorts?.map((item) => ({
      ...presentFields(item),
      fieldCode: text(item.fieldCode, 'sort field'),
      direction: choice(
        typeof item.direction === 'string' ? item.direction.toLowerCase() : item.direction,
        ['asc', 'desc'],
        'sort direction',
      ),
    })),
    filters: config.filters?.map((item) => ({
      ...presentFields(item),
      fieldCode: text(item.fieldCode, 'filter field'),
      operator: choice(
        item.operator,
        ['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'like', 'in', 'between', 'isNull', 'isNotNull'],
        'filter operator',
      ),
      logic: filterLogic(item.logic),
      value: item.value,
    })),
    groupBy: config.groupBy?.map((item) => ({
      ...presentFields(item),
      fieldCode: text(item.fieldCode, 'grouping field'),
      aggregations: item.aggregations?.map((aggregation) => ({
        ...presentFields(aggregation),
        fieldCode: text(aggregation.fieldCode, 'aggregation field'),
        function: choice(
          aggregation.function,
          ['count', 'sum', 'avg', 'min', 'max'],
          'aggregation function',
        ),
      })),
    })),
    pagination: config.pagination == null ? undefined : presentFields(config.pagination),
    conditionalFormats: config.conditionalFormats?.map(conditionalFormat),
    toolbarActions: config.toolbarActions?.map((item) => {
      if (
        typeof item.visible !== 'boolean' ||
        typeof item.pinned !== 'boolean' ||
        typeof item.order !== 'number'
      ) {
        throw new Error('Saved view has invalid action settings');
      }
      return {
        ...presentFields(item),
        code: text(item.code, 'action identity'),
        visible: item.visible,
        pinned: item.pinned,
        order: item.order,
      };
    }),
    cardFields: config.cardFields?.map((item) => ({
      ...presentFields(item),
      field: text(item.field, 'card field'),
      label: localized(item.label),
    })),
    kanbanAggregations: config.kanbanAggregations?.map((item) => ({
      ...presentFields(item),
      // A count aggregation legitimately carries no field (useKanbanData aggregates the card
      // count); plugin-imported kanban presets ship exactly that shape. Only field-carrying
      // functions must name their column.
      field:
        item.function === 'count' && (item.field == null || item.field === '')
          ? ''
          : text(item.field, 'card aggregation field'),
      label: localized(item.label),
      function: choice(
        item.function,
        ['count', 'sum', 'avg', 'min', 'max'],
        'aggregation function',
      ),
    })),
    meta,
  };
}

export function savedViewForView(data: SavedViewWire): SavedView {
  if (!data || typeof data !== 'object' || Array.isArray(data))
    throw new Error('Invalid saved view response');
  return {
    ...presentFields(data),
    pid: text(data.pid, 'identity'),
    name: text(data.name, 'name'),
    modelCode: text(data.modelCode, 'model'),
    scope: choice<ViewScope>(data.scope, ['personal', 'team', 'role', 'global'], 'scope'),
    viewType:
      data.viewType == null
        ? undefined
        : choice<ViewType>(
            data.viewType.toLowerCase(),
            ['table', 'kanban', 'calendar', 'gallery', 'gantt', 'tree', 'timeline', 'form'],
            'view type',
          ),
    viewConfig: savedViewConfigForView(data.viewConfig),
  };
}

export function savedViewConfigToWire(
  config: Partial<ViewConfig> | undefined,
): SavedViewCreateWire['viewConfig'] {
  if (config === undefined) return undefined;
  return {
    ...config,
    filters: config.filters?.map((item) => ({
      ...item,
      value: item.value === undefined ? undefined : savedViewJson(item.value),
    })),
    conditionalFormats: config.conditionalFormats?.map((item) => {
      const json = savedViewJson(item);
      if (!json || typeof json !== 'object' || Array.isArray(json))
        throw new Error('Invalid saved view formatting');
      return json;
    }),
  };
}

function reasonForView(item: SavedViewReasonWire): SavedViewCapabilityReason {
  return {
    ...presentFields(item),
    code: text(item.code, 'capability reason'),
    message: text(item.message, 'capability message'),
  };
}
export function savedViewCapabilityForView(
  data: SavedViewCapabilityWire,
): SavedViewCapabilityCheckResponse {
  if (!Array.isArray(data.missingFields) || !Array.isArray(data.reasons))
    throw new Error('Invalid saved view capability response');
  return {
    ...data,
    viewType: choice(
      typeof data.viewType === 'string' ? data.viewType.toLowerCase() : data.viewType,
      ['table', 'kanban', 'calendar', 'gallery', 'gantt', 'tree', 'timeline', 'form'],
      'view type',
    ),
    status: choice(data.status, ['available', 'degraded', 'blocked'], 'capability status'),
    missingFields: data.missingFields,
    reasons: data.reasons.map(reasonForView),
  };
}
