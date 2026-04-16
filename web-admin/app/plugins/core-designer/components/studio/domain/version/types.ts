/**
 * Version management domain types for Studio.
 * Copied from the legacy designer layer to remove direct dependencies.
 */

import type { CanvasSchema } from '~/plugins/core-designer/components/studio/workbench/canvas/types';

export enum VersionStatus {
  draft = 'draft',
  published = 'published',
  archived = 'archived',
}

export enum VersionType {
  MAJOR = 'major',
  MINOR = 'minor',
  PATCH = 'patch',
  SNAPSHOT = 'snapshot',
}

export interface Version {
  id: string;
  version: string;
  status: VersionStatus;
  type: VersionType;
  schema: CanvasSchema;
  createdAt: Date | string;
  updatedAt: Date | string;
  createdBy: string;
  updatedBy: string;
  description?: string;
  changelog?: string;
  tags?: string[];
  isCurrent?: boolean;
  parentVersionId?: string;
  publishedAt?: Date | string;
  publishedBy?: string;
}

export interface VersionDifference {
  type: 'added' | 'modified' | 'deleted';
  path: string;
  oldValue?: any;
  newValue?: any;
  description: string;
}

export interface VersionDiff {
  versionA: Version;
  versionB: Version;
  differences: VersionDifference[];
  stats: {
    added: number;
    modified: number;
    deleted: number;
  };
}

export interface CreateVersionRequest {
  pageId: string;
  schema?: CanvasSchema;
  type: VersionType;
  description?: string;
  changelog?: string;
  tags?: string[];
  baseVersionId?: string;
}

export interface UpdateVersionRequest {
  versionId: string;
  schema?: CanvasSchema;
  description?: string;
  changelog?: string;
  tags?: string[];
}

export interface PublishVersionRequest {
  versionId: string;
  description?: string;
  force?: boolean;
}

export interface RollbackVersionRequest {
  pageId: string;
  targetVersionId: string;
  description?: string;
  createNewVersion?: boolean;
}

export interface VersionQueryParams {
  page?: number;
  size?: number;
  sortBy?: 'createdAt' | 'updatedAt' | 'version';
  sortOrder?: 'asc' | 'desc';
  status?: VersionStatus;
  type?: VersionType;
  tags?: string[];
}

export interface VersionListResponse {
  versions: Version[];
  pagination: {
    page: number;
    size: number;
    total: number;
    totalPages: number;
  };
}
