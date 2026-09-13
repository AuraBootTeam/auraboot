import type { ReportDsl } from '../types';

/** Bind query inputs without mutating the saved report definition. */
export function applyReportParameters(
  report: ReportDsl,
  inputs: Record<string, string> = {},
): ReportDsl {
  const dataSources = { ...report.dataSources };
  const accepted = new Set<string>();
  for (const parameter of report.parameters ?? []) {
    const { name, type, bindTo } = parameter;
    let value = Object.hasOwn(inputs, name) ? inputs[name] : parameter.defaultValue;
    let values: string[] | undefined;
    if (type === 'date-range') {
      accepted.add(`${name}_start`);
      accepted.add(`${name}_end`);
      const start = inputs[`${name}_start`],
        end = inputs[`${name}_end`];
      if (start || end) {
        if (!validDate(start) || !validDate(end) || start > end)
          throw new Error('Invalid report date range');
        values = [start, end];
      }
      value = undefined;
    } else accepted.add(name);
    if (!value?.trim() && !values) {
      if (parameter.required)
        throw new Error(`Required report parameter is missing: ${parameter.label}`);
      continue;
    }
    if (!bindTo || !dataSources[bindTo.dataSource] || !bindTo.field)
      throw new Error('Invalid report parameter binding');
    const source = dataSources[bindTo.dataSource];
    if (source.type !== 'model')
      throw new Error('Report filter parameters require a model data source');
    if (type === 'number' && !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value ?? ''))
      throw new Error('Invalid numeric report parameter');
    if (type === 'date' && !validDate(value)) throw new Error('Invalid report parameter date');
    if (type === 'select' && !parameter.options?.some((option) => option.value === value))
      throw new Error('Invalid report parameter option');
    dataSources[bindTo.dataSource] = {
      ...source,
      filters: [
        ...(source.filters ?? []),
        values
          ? { field: bindTo.field, operator: 'BETWEEN', values }
          : {
              field: bindTo.field,
              operator: bindTo.operator,
              value,
            },
      ],
    };
  }
  if (Object.keys(inputs).some((key) => !accepted.has(key)))
    throw new Error('Unknown report parameter');
  return { ...report, dataSources };
}

function validDate(value: string | undefined): value is string {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}
