/**
 * Version Status Type Definitions
 *
 * Unified status definition for all versioned entities (Model, Field, Dict, PageSchema, etc.)
 */

/**
 * Version Status Enum
 */
export type VersionStatus =
  | 'draft' // Draft: being edited, not published
  | 'published' // Published: current active version
  | 'deprecated' // Deprecated: not recommended but still accessible
  | 'archived' // Archived: historical version, readonly
  | 'disabled'; // Disabled: temporarily unavailable

/**
 * Status Badge Configuration
 */
export interface StatusBadgeConfig {
  color: 'gray' | 'green' | 'orange' | 'blue' | 'red';
  label: string;
  description: string;
}

/**
 * Status Badge Configuration Map
 */
export const STATUS_BADGE_CONFIG: Record<VersionStatus, StatusBadgeConfig> = {
  draft: {
    color: 'gray',
    label: '草稿',
    description: '正在编辑，未发布',
  },
  published: {
    color: 'green',
    label: '已发布',
    description: '当前生效版本',
  },
  deprecated: {
    color: 'orange',
    label: '已废弃',
    description: '不推荐使用但仍可用',
  },
  archived: {
    color: 'blue',
    label: '已归档',
    description: '历史版本，只读',
  },
  disabled: {
    color: 'red',
    label: '已禁用',
    description: '不可用',
  },
};

/**
 * Status Transition Map
 */
export const STATUS_TRANSITIONS: Record<VersionStatus, VersionStatus[]> = {
  draft: ['published', 'disabled'],
  published: ['deprecated', 'archived', 'disabled'],
  deprecated: ['archived', 'disabled'],
  archived: ['published', 'disabled'], // Support rollback
  disabled: ['draft', 'published', 'deprecated', 'archived'], // Can restore
};

/**
 * Check if status transition is allowed
 */
export function canTransitionTo(from: VersionStatus, to: VersionStatus): boolean {
  return STATUS_TRANSITIONS[from]?.includes(to) ?? false;
}

/**
 * Get allowed transition targets
 */
export function getAllowedTransitions(status: VersionStatus): VersionStatus[] {
  return STATUS_TRANSITIONS[status] ?? [];
}

/**
 * Check if status is active (can be used in business logic)
 */
export function isActiveStatus(status: VersionStatus): boolean {
  return status === 'published' || status === 'deprecated';
}

/**
 * Check if status is editable
 */
export function isEditableStatus(status: VersionStatus): boolean {
  return status === 'draft';
}

/**
 * Check if status is readonly
 */
export function isReadonlyStatus(status: VersionStatus): boolean {
  return status === 'deprecated' || status === 'archived';
}

/**
 * Get status display info
 */
export function getStatusDisplay(status: VersionStatus): StatusBadgeConfig {
  return STATUS_BADGE_CONFIG[status];
}

/**
 * Status Action Configuration
 */
export interface StatusAction {
  key: string;
  label: string;
  targetStatus: VersionStatus;
  icon?: string;
  confirmMessage?: string;
  requirePermission?: string;
}

/**
 * Get available actions for current status
 */
export function getStatusActions(currentStatus: VersionStatus): StatusAction[] {
  const actions: StatusAction[] = [];
  const allowedTransitions = getAllowedTransitions(currentStatus);

  for (const targetStatus of allowedTransitions) {
    const action = getStatusAction(currentStatus, targetStatus);
    if (action) {
      actions.push(action);
    }
  }

  return actions;
}

/**
 * Get status action configuration
 */
function getStatusAction(from: VersionStatus, to: VersionStatus): StatusAction | null {
  const actionMap: Record<string, StatusAction> = {
    'draft->published': {
      key: 'publish',
      label: '发布',
      targetStatus: 'published',
      icon: 'check-circle',
      confirmMessage: '确定要发布此版本吗？发布后将成为当前生效版本。',
      requirePermission: 'publish',
    },
    'published->deprecated': {
      key: 'deprecate',
      label: '标记为废弃',
      targetStatus: 'deprecated',
      icon: 'exclamation-circle',
      confirmMessage: '确定要标记为废弃吗？废弃后不推荐使用但仍可访问。',
      requirePermission: 'deprecate',
    },
    'published->archived': {
      key: 'archive',
      label: '归档',
      targetStatus: 'archived',
      icon: 'inbox',
      confirmMessage: '确定要归档此版本吗？归档后将变为只读状态。',
      requirePermission: 'archive',
    },
    'deprecated->archived': {
      key: 'archive',
      label: '归档',
      targetStatus: 'archived',
      icon: 'inbox',
      confirmMessage: '确定要归档此版本吗？归档后将变为只读状态。',
      requirePermission: 'archive',
    },
    'archived->published': {
      key: 'rollback',
      label: '回滚激活',
      targetStatus: 'published',
      icon: 'rollback',
      confirmMessage: '确定要回滚到此版本吗？此版本将成为当前生效版本。',
      requirePermission: 'rollback',
    },
  };

  // Handle disable action (available from any status)
  if (to === 'disabled') {
    return {
      key: 'disable',
      label: '禁用',
      targetStatus: 'disabled',
      icon: 'stop',
      confirmMessage: '确定要禁用吗？禁用后将不可用。',
      requirePermission: 'disable',
    };
  }

  // Handle enable action (restore from disabled)
  if (from === 'disabled') {
    return {
      key: 'enable',
      label: '启用',
      targetStatus: to,
      icon: 'play-circle',
      confirmMessage: `确定要启用并恢复到${STATUS_BADGE_CONFIG[to].label}状态吗？`,
      requirePermission: 'enable',
    };
  }

  const key = `${from}->${to}`;
  return actionMap[key] || null;
}
