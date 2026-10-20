import React from 'react';
import { readDataSourceRecord, readDataSourceState, useDataSourceSubscription } from '../../blocks/workbenchBlockUtils';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
export function WorkspaceTabCount({ config, record, runtime }: { config: { field?: string; dataSource?: string; valueField?: string }; record: Record<string, any>; runtime: SchemaRuntime }) {
  useDataSourceSubscription(runtime, config.dataSource);
  let count: number | undefined;
  if (config.field) {
    const value = record[config.field];
    if (Array.isArray(value)) count = value.length;
    else if (typeof value === 'string') { try { const parsed = JSON.parse(value); if (Array.isArray(parsed)) count = parsed.length; } catch { /* Invalid values must not produce invented counts. */ } }
    else if (value == null) count = 0;
  } else if (config.dataSource && readDataSourceState(runtime, config.dataSource)?.lastFetch != null && !readDataSourceState(runtime, config.dataSource)?.error) {
    const value = readDataSourceRecord(runtime, config.dataSource)[config.valueField || 'total'];
    if (value != null && Number.isFinite(Number(value))) count = Number(value);
  }
  return count === undefined ? null : <span className="bg-subtle text-text-3 ml-2 rounded px-1.5 py-0.5 text-[11px]" data-testid={`tab-count-${config.field || config.dataSource}`}>{count}</span>;
}
