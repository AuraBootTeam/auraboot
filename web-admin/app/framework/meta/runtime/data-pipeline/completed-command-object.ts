import type { DataSourceConfig } from '../../schemas/types';
import { readOwnPath } from '../expression/row-projection';

/** Adapt one completed command payload without treating errors as empty objects. */
export function completedCommandObject(
  command: unknown,
  config: DataSourceConfig,
): Record<string, unknown> {
  if (readOwnPath(command, 'phaseReached') !== 'completed')
    throw new Error('Expected completed command response');
  if (config.rowProjection !== undefined || config.rowIdentityField !== undefined)
    throw new Error('Object command sources do not support row projection');
  const payload = readOwnPath(command, 'data');
  const value = config.dataPath === undefined ? payload : readOwnPath(payload, config.dataPath);
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    ![Object.prototype, null].includes(Object.getPrototypeOf(value))
  )
    throw new Error('Expected a command object at the configured data path');
  return value as Record<string, unknown>;
}
