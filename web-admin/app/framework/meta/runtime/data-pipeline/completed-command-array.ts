import type { DataSourceConfig } from '../../schemas/types';
import { boundedRecordRows, readOwnPath } from '../expression/row-projection';

/** No phase, path or pagination fallback: incomplete commands cannot look empty. */
export function completedCommandArray(
  command: unknown,
  config: DataSourceConfig,
): Record<string, unknown>[] {
  if (readOwnPath(command, 'phaseReached') !== 'completed')
    throw new Error('Expected completed command response');
  return boundedRecordRows(readOwnPath(readOwnPath(command, 'data'), config.dataPath ?? ''));
}

export function completedCommandOptions(command: unknown, config: DataSourceConfig) {
  const rows = completedCommandArray(command, config);
  const total = readOwnPath(readOwnPath(command, 'data'), config.totalPath ?? 'total');
  if (!Number.isSafeInteger(total) || total !== rows.length)
    throw new Error('Incomplete command options collection');
  const identities = new Set<string>();
  return rows.map((row) => {
    const value = readOwnPath(row, config.valueField ?? 'value');
    const label = readOwnPath(row, config.labelField ?? 'label');
    if (
      typeof value !== 'string' ||
      !value.trim() ||
      identities.has(value) ||
      typeof label !== 'string'
    ) {
      throw new Error('Invalid command option identity or label');
    }
    identities.add(value);
    if (!config.selectableField) return { value, label };
    const selectable = readOwnPath(row, config.selectableField);
    if (typeof selectable !== 'boolean') throw new Error('Invalid command option availability');
    return { value, label, disabled: !selectable };
  });
}
