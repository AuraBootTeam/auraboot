/**
 * useSmartDefaults — Generate sensible block defaults from model field metadata
 *
 * When a user selects a model on a block for the first time, these helpers
 * produce a reasonable starting config so the user doesn't have to configure
 * everything from scratch.
 *
 * @since 4.0.0
 */

import { useCallback } from 'react';

export interface ModelField {
  fieldCode: string;
  fieldName: string;
  dataType: string;
  required: boolean;
}

/** Platform-managed system columns that should be hidden from UI blocks */
const SYSTEM_FIELDS = [
  'created_at',
  'updated_at',
  'deleted_flag',
  'tenant_id',
  'created_by',
  'updated_by',
  'pid',
  'version',
];

/** Default column descriptor shape used by TableBlock */
export interface DefaultColumn {
  fieldCode: string;
  label: string;
  dataType: string;
}

export interface TableDefaults {
  columns: DefaultColumn[];
  features: {
    search: boolean;
    filter: boolean;
    sort: boolean;
    pagination: { enabled: boolean; pageSize: number };
    create: { enabled: boolean };
    rowActions: Array<{
      label: string;
      action: { type: 'command' };
      confirm?: boolean;
      danger?: boolean;
    }>;
  };
  rowClick: { type: 'drawer' };
}

export interface FormDefaults {
  fields: string[];
  columns: number;
}

export interface ChartDefaults {
  metricField: string | undefined;
  aggregation: string;
  groupField: string | undefined;
  chartType: string;
}

export interface UseSmartDefaultsResult {
  generateTableDefaults: (fields: ModelField[]) => TableDefaults;
  generateFormDefaults: (fields: ModelField[]) => FormDefaults;
  generateChartDefaults: (fields: ModelField[]) => ChartDefaults;
}

/**
 * Returns helper functions to generate smart defaults for canvas blocks.
 */
export function useSmartDefaults(): UseSmartDefaultsResult {
  const generateTableDefaults = useCallback((fields: ModelField[]): TableDefaults => ({
    columns: fields
      .filter((f) => !SYSTEM_FIELDS.includes(f.fieldCode))
      .map((f) => ({ fieldCode: f.fieldCode, label: f.fieldName, dataType: f.dataType })),
    features: {
      search: true,
      filter: true,
      sort: true,
      pagination: { enabled: true, pageSize: 20 },
      create: { enabled: true },
      rowActions: [
        { label: 'Edit', action: { type: 'command' as const } },
        { label: 'Delete', action: { type: 'command' as const }, confirm: true, danger: true },
      ],
    },
    rowClick: { type: 'drawer' as const },
  }), []);

  const generateFormDefaults = useCallback((fields: ModelField[]): FormDefaults => ({
    fields: fields
      .filter((f) => !SYSTEM_FIELDS.includes(f.fieldCode))
      .map((f) => f.fieldCode),
    columns: 3,
  }), []);

  const generateChartDefaults = useCallback((fields: ModelField[]): ChartDefaults => {
    const decimalField = fields.find((f) =>
      ['DECIMAL', 'INTEGER', 'BIGINT'].includes(f.dataType),
    );
    const enumField = fields.find((f) => f.dataType === 'ENUM');
    return {
      metricField: decimalField?.fieldCode,
      aggregation: 'sum',
      groupField: enumField?.fieldCode,
      chartType: 'bar',
    };
  }, []);

  return { generateTableDefaults, generateFormDefaults, generateChartDefaults };
}
