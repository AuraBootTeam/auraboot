/**
 * Version History Types
 *
 * Types for the version history panel.
 *
 * @since 3.2.0
 */

/**
 * Version operation type
 */
export type VersionOperation =
  | 'create'
  | 'update'
  | 'publish'
  | 'archive'
  | 'delete'
  | 'restore'
  | 'rollback';

/**
 * Version entry
 */
export interface VersionEntry {
  /** Version history ID */
  id: number;
  /** Page PID */
  pagePid: string;
  /** Version number */
  version: number;
  /** Semantic version string */
  semver?: string;
  /** Operation type */
  operation: VersionOperation;
  /** Operator user */
  operator?: string;
  /** Operation timestamp */
  timestamp: string;
  /** Snapshot data */
  snapshot?: Record<string, unknown>;
}

/**
 * Version comparison result
 */
export interface VersionComparison {
  /** Source version */
  fromVersion: VersionEntry;
  /** Target version */
  toVersion: VersionEntry;
  /** Changes list */
  changes: VersionChange[];
}

/**
 * Version change detail
 */
export interface VersionChange {
  /** Changed field path */
  field: string;
  /** Old value */
  oldValue?: unknown;
  /** New value */
  newValue?: unknown;
  /** Change type */
  changeType: 'add' | 'remove' | 'modify';
}

/**
 * Panel view mode
 */
export type ViewMode = 'list' | 'compare';

/**
 * Operation display info
 */
export const OPERATION_INFO: Record<
  VersionOperation,
  { label: string; color: string; bgColor: string; icon: string }
> = {
  create: {
    label: '创建',
    color: 'text-green-700',
    bgColor: 'bg-green-100',
    icon: 'M12 4v16m8-8H4',
  },
  update: {
    label: '更新',
    color: 'text-blue-700',
    bgColor: 'bg-blue-100',
    icon: 'M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z',
  },
  publish: {
    label: '发布',
    color: 'text-purple-700',
    bgColor: 'bg-purple-100',
    icon: 'M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12',
  },
  archive: {
    label: '归档',
    color: 'text-gray-700',
    bgColor: 'bg-gray-100',
    icon: 'M5 8h14M5 8a2 2 0 110-4h14a2 2 0 110 4M5 8v10a2 2 0 002 2h10a2 2 0 002-2V8m-9 4h4',
  },
  delete: {
    label: '删除',
    color: 'text-red-700',
    bgColor: 'bg-red-100',
    icon: 'M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16',
  },
  restore: {
    label: '恢复',
    color: 'text-teal-700',
    bgColor: 'bg-teal-100',
    icon: 'M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15',
  },
  rollback: {
    label: '回滚',
    color: 'text-orange-700',
    bgColor: 'bg-orange-100',
    icon: 'M3 10h10a8 8 0 018 8v2M3 10l6 6m-6-6l6-6',
  },
};
