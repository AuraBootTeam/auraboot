import { encodeFilters } from '~/framework/meta/rendering/pages/list/useListUrlState';
import type { ViewFilterConfig } from '~/framework/smart/types/savedView';
import type {
  DrillDownConfig,
  DrillDownFilterRule,
  FilterConfig,
} from '~/framework/smart/types/chart';

export type DrillDownPayload = DrillDownConfig | FilterConfig[];

function nextMonthStart(month: string): string | null {
  const match = /^(\d{4})-(\d{2})$/.exec(month);
  if (!match) return null;
  const year = Number(match[1]);
  const monthIndex = Number(match[2]) - 1;
  if (monthIndex < 0 || monthIndex > 11) return null;
  const next = new Date(Date.UTC(year, monthIndex + 1, 1));
  return `${next.getUTCFullYear()}-${String(next.getUTCMonth() + 1).padStart(2, '0')}-01`;
}

function materializeRule(
  rule: DrillDownFilterRule,
  payload: FilterConfig[],
): ViewFilterConfig[] {
  const sourceValue = rule.sourceField
    ? payload.find((filter) => filter.field === rule.sourceField)?.value
    : rule.value;
  const value = rule.value !== undefined ? rule.value : sourceValue;
  if (value == null || value === '') return [];

  if (rule.transform === 'month-range') {
    const month = String(value);
    const nextStart = nextMonthStart(month);
    if (!nextStart) return [];
    return [
      { fieldCode: rule.targetField, operator: 'gte', value: `${month}-01` },
      { fieldCode: rule.targetField, operator: 'lt', value: nextStart },
    ];
  }

  return [
    {
      fieldCode: rule.targetField,
      operator: rule.operator ?? 'eq',
      value,
    },
  ];
}

export function buildDrillDownTarget(
  config: DrillDownConfig,
  payload: FilterConfig[] = [],
): string | null {
  if (!config.enabled || config.action !== 'navigate' || !config.targetPage) return null;

  const params = new URLSearchParams();
  const exactFilters = (config.filters ?? []).flatMap((rule) =>
    materializeRule(rule, payload),
  );
  const encodedFilters = encodeFilters(exactFilters);
  if (encodedFilters) {
    params.set('filters', encodedFilters);
  } else if (payload.length > 0) {
    payload.forEach((filter) => {
      const paramName = config.paramMapping?.[filter.field] || filter.field;
      params.set(paramName, String(filter.value));
    });
  } else {
    Object.entries(config.paramMapping ?? {}).forEach(([field, value]) => {
      params.set(`filter_${field}`, String(value));
    });
  }

  const query = params.toString();
  return query ? `${config.targetPage}?${query}` : config.targetPage;
}

export function resolveDashboardRuntimeValue<T>(
  value: T,
  runtimeParams: Record<string, string | null | undefined>,
): T {
  if (typeof value === 'string') {
    return value.replace(/\$\{([A-Za-z0-9_]+)\}/g, (match, key) => {
      const replacement = runtimeParams[key];
      return replacement == null ? match : replacement;
    }) as T;
  }
  if (Array.isArray(value)) {
    return value.map((entry) => resolveDashboardRuntimeValue(entry, runtimeParams)) as T;
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([key, entry]) => [
        key,
        resolveDashboardRuntimeValue(entry, runtimeParams),
      ]),
    ) as T;
  }
  return value;
}
