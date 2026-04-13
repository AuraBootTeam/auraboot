import type { Version } from '~/plugins/core-designer/components/studio/domain/metadata/types';
import { VersionStatus, VersionType } from '~/plugins/core-designer/components/studio/domain/metadata/types';

// 工具函数
export const VersionUtils = {
  /**
   * 生成版本号
   */
  generateVersionNumber: (lastVersion?: string): string => {
    if (!lastVersion) {
      return '1.0.0';
    }

    const parts = lastVersion.split('.').map(Number);
    if (parts.length !== 3) {
      return '1.0.0';
    }

    // 增加补丁版本号
    parts[2] += 1;
    return parts.join('.');
  },

  /**
   * 比较版本号
   */
  compareVersions: (versionA: string, versionB: string): number => {
    const partsA = versionA.split('.').map(Number);
    const partsB = versionB.split('.').map(Number);

    for (let i = 0; i < Math.max(partsA.length, partsB.length); i++) {
      const a = partsA[i] || 0;
      const b = partsB[i] || 0;

      if (a > b) return 1;
      if (a < b) return -1;
    }

    return 0;
  },

  /**
   * 验证版本号格式
   */
  isValidVersionNumber: (version: string): boolean => {
    const versionRegex = /^\d+\.\d+\.\d+$/;
    return versionRegex.test(version);
  },

  /**
   * 格式化版本状态
   */
  formatVersionStatus: (status: VersionStatus): string => {
    const statusMap = {
      [VersionStatus.draft]: '草稿',
      [VersionStatus.published]: '已发布',
      [VersionStatus.archived]: '已归档',
    };
    return statusMap[status] || status;
  },

  /**
   * 格式化版本类型
   */
  formatVersionType: (type: VersionType): string => {
    const typeMap = {
      [VersionType.MAJOR]: '主版本',
      [VersionType.MINOR]: '次版本',
      [VersionType.PATCH]: '补丁版本',
      [VersionType.HOTFIX]: '热修复',
      [VersionType.SNAPSHOT]: '快照',
    };
    return typeMap[type] || type;
  },

  /**
   * 获取版本状态颜色
   */
  getVersionStatusColor: (status: VersionStatus): string => {
    const colorMap = {
      [VersionStatus.draft]: 'orange',
      [VersionStatus.published]: 'green',
      [VersionStatus.archived]: 'gray',
    };
    return colorMap[status] || 'gray';
  },

  /**
   * 获取版本类型图标
   */
  getVersionTypeIcon: (type: VersionType): string => {
    const iconMap = {
      [VersionType.MAJOR]: '🚀',
      [VersionType.MINOR]: '✨',
      [VersionType.PATCH]: '🔧',
      [VersionType.HOTFIX]: '🚨',
      [VersionType.SNAPSHOT]: '📸',
    };
    return iconMap[type] || '📄';
  },

  /**
   * 创建版本摘要
   */
  createVersionSummary: (version: Version): string => {
    const status = VersionUtils.formatVersionStatus(version.status);
    const type = VersionUtils.formatVersionType(version.type);
    return `${version.version} (${type}, ${status})`;
  },

  /**
   * 检查版本是否可编辑
   */
  isVersionEditable: (version: Version): boolean => {
    return version.status === VersionStatus.draft;
  },

  /**
   * 检查版本是否可发布
   */
  isVersionPublishable: (version: Version): boolean => {
    return version.status === VersionStatus.draft;
  },

  /**
   * 检查版本是否可回滚
   */
  isVersionRollbackable: (version: Version): boolean => {
    return version.status === VersionStatus.published;
  },

  /**
   * 检查版本是否可归档
   */
  isVersionArchivable: (version: Version): boolean => {
    return version.status !== VersionStatus.archived;
  },

  /**
   * 检查版本是否可恢复
   */
  isVersionRestorable: (version: Version): boolean => {
    return version.status === VersionStatus.archived;
  },

  /**
   * 获取版本操作权限
   */
  getVersionPermissions: (version: Version) => {
    return {
      canEdit: VersionUtils.isVersionEditable(version),
      canPublish: VersionUtils.isVersionPublishable(version),
      canRollback: VersionUtils.isVersionRollbackable(version),
      canArchive: VersionUtils.isVersionArchivable(version),
      canRestore: VersionUtils.isVersionRestorable(version),
      canDelete: version.status === VersionStatus.draft,
      canDuplicate: true,
      canCompare: true,
    };
  },
};
