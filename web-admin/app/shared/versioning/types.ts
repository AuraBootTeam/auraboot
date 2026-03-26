/**
 * Shared versioning types for all designers.
 */

/**
 * Version history entry from the backend.
 */
export interface VersionEntry {
  pid: string;
  resourceType: string;
  resourceId: string;
  version: string;
  operation: string;
  operationBy: string;
  operationAt: string;
  description?: string;
  parentVersionId?: string;
  /** Full snapshot (only included when fetching a single version) */
  schemaSnapshot?: Record<string, unknown>;
}

/**
 * Version operation types
 */
export type VersionOperation =
  | 'create'
  | 'update'
  | 'publish'
  | 'unpublish'
  | 'archive'
  | 'rollback'
  | 'backup_before_rollback';

/**
 * Operation display configuration
 */
export interface OperationConfig {
  label: string;
  borderColor: string;
  badgeBg: string;
  badgeText: string;
}

/**
 * Default operation display configuration
 */
export const OPERATION_CONFIGS: Record<string, OperationConfig> = {
  CREATE: {
    label: 'Created',
    borderColor: 'border-l-blue-400',
    badgeBg: 'bg-blue-100',
    badgeText: 'text-blue-700',
  },
  UPDATE: {
    label: 'Updated',
    borderColor: 'border-l-gray-400',
    badgeBg: 'bg-gray-100',
    badgeText: 'text-gray-700',
  },
  PUBLISH: {
    label: 'Published',
    borderColor: 'border-l-green-500',
    badgeBg: 'bg-green-100',
    badgeText: 'text-green-700',
  },
  UNPUBLISH: {
    label: 'Unpublished',
    borderColor: 'border-l-yellow-500',
    badgeBg: 'bg-yellow-100',
    badgeText: 'text-yellow-700',
  },
  ARCHIVE: {
    label: 'Archived',
    borderColor: 'border-l-red-400',
    badgeBg: 'bg-red-100',
    badgeText: 'text-red-700',
  },
  rollback: {
    label: 'Rollback',
    borderColor: 'border-l-purple-500',
    badgeBg: 'bg-purple-100',
    badgeText: 'text-purple-700',
  },
  BACKUP_BEFORE_ROLLBACK: {
    label: 'Backup',
    borderColor: 'border-l-gray-300',
    badgeBg: 'bg-gray-50',
    badgeText: 'text-gray-500',
  },
};

export function getOperationConfig(operation: string): OperationConfig {
  return OPERATION_CONFIGS[operation] || OPERATION_CONFIGS.UPDATE;
}
