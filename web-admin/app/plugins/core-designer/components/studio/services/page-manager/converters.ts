/**
 * Type Converters
 *
 * Convert between API types (wire format) and frontend types (domain model).
 *
 * @since 3.2.0
 */

import type {
  PageSchemaDTO,
  PageSchemaCreateRequest,
  ApiPageStatus,
} from './api-types';
import type { PageMeta, PageStatus, CreatePageRequest } from './types';
import type { PageSchema } from '../../domain/dsl/types';

// Kinds handled by Dashboard Designer or deprecated — toPageSchema rejects these.
const UNSUPPORTED_KINDS = new Set(['dashboard', 'home', 'composite', 'custom']);

/**
 * Convert PageSchemaDTO to PageSchema (V2 flat shape).
 * Rejects dashboard/home/composite — those go through Dashboard Designer.
 * Throws if blocks array is missing.
 */
export function toPageSchema(dto: PageSchemaDTO): PageSchema {
  if (UNSUPPORTED_KINDS.has(dto.kind as string)) {
    throw new Error(
      `PageSchema does not support kind='${dto.kind}'. ` +
        `Dashboard is handled by Dashboard Designer; home/composite are deprecated.`,
    );
  }
  if (!Array.isArray(dto.blocks)) {
    throw new Error(`PageSchema requires blocks array (pid=${dto.pid})`);
  }
  return {
    schemaVersion: 2,
    kind: dto.kind as PageSchema['kind'],
    id: dto.pid,
    pageKey: dto.pageKey,
    modelCode: dto.extension?.viewModelCode as string | undefined,
    title: dto.title,
    layout: (dto.layout as PageSchema['layout']) ?? { type: 'stack' },
    blocks: dto.blocks as PageSchema['blocks'],
    profile: dto.profile as PageSchema['profile'],
    extension: dto.extension,
  };
}

/**
 * Map backend ApiPageStatus to frontend PageStatus
 */
export function fromApiPageStatus(apiStatus: ApiPageStatus, isPublished?: boolean): PageStatus {
  if (apiStatus === 'published' || isPublished) {
    return 'published';
  }
  if (apiStatus === 'archived') {
    return 'archived';
  }
  return 'draft';
}

/**
 * Map frontend PageStatus to backend ApiPageStatus
 */
export function toApiPageStatus(status: PageStatus): ApiPageStatus {
  const mapping: Record<PageStatus, ApiPageStatus> = {
    draft: 'draft',
    published: 'published',
    modified: 'draft', // Modified is still a draft in backend
    archived: 'archived',
  };
  return mapping[status];
}

/**
 * Convert PageSchemaDTO to PageMeta.
 * V2: uses kind instead of mode; no dslSchema synthesis.
 */
export function toPageMeta(dto: PageSchemaDTO): PageMeta {
  // Extract tags from the tags object
  let tags: string[] = [];
  if (dto.tags) {
    if (Array.isArray(dto.tags)) {
      tags = dto.tags as unknown as string[];
    } else if (typeof dto.tags === 'object' && dto.tags.list) {
      tags = dto.tags.list as string[];
    }
  }

  return {
    id: dto.pid,
    pageKey: dto.pageKey,
    title:
      (typeof dto.title === 'string'
        ? dto.title
        : dto.title?.['en-US'] || dto.title?.['zh-CN']) || dto.name,
    description: dto.description,
    kind: dto.kind as PageMeta['kind'],
    viewModelCode: dto.extension?.viewModelCode as string | undefined,
    status: fromApiPageStatus(dto.status || 'draft', dto.isPublished),
    version: dto.semver || `${dto.version || 1}.0.0`,
    publishedVersion: dto.isPublished ? dto.semver : undefined,
    createdAt: dto.createdAt || new Date().toISOString(),
    updatedAt: dto.updatedAt || new Date().toISOString(),
    tags,
    thumbnail: dto.metaInfo?.thumbnail as string | undefined,
    componentCount: dto.blocks?.length ?? 0,
    extension: dto.extension,
  };
}

/**
 * Convert PageMeta to PageSchemaUpdateRequest (partial)
 */
export function toUpdateRequest(page: Partial<PageMeta>): Record<string, unknown> {
  const request: Record<string, unknown> = {};

  if (page.title !== undefined) {
    request.title = page.title;
  }
  if (page.description !== undefined) {
    request.description = page.description;
  }
  if (page.tags !== undefined) {
    request.tags = { list: page.tags };
  }
  if (page.kind !== undefined) {
    request.kind = page.kind;
  }

  return request;
}

/**
 * Generate a unique page key from title
 * Key must: start with letter, contain only letters, numbers, underscores, and hyphens
 */
function generatePageKey(title: string): string {
  // Convert title to a valid key format
  let baseKey = title
    .replace(/[^a-zA-Z0-9_-]+/g, '_') // Replace invalid chars with underscore
    .replace(/^[^a-zA-Z]+/, '') // Remove leading non-letters
    .replace(/_+/g, '_') // Collapse multiple underscores
    .replace(/^_|_$/g, '') // Remove leading/trailing underscores
    .slice(0, 30); // Limit length

  // Ensure it starts with a letter, if empty or starts with number, add prefix
  if (!baseKey || !/^[a-zA-Z]/.test(baseKey)) {
    baseKey = 'page';
  }

  // Add timestamp suffix for uniqueness
  const suffix = Date.now().toString(36);
  return `${baseKey}_${suffix}`;
}

/**
 * Convert CreatePageRequest to PageSchemaCreateRequest
 */
export function toCreateRequest(request: CreatePageRequest): PageSchemaCreateRequest {
  // Generate a valid name from title (alphanumeric with underscores)
  const name = `page_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
  // Use provided pageKey or generate one
  const pageKey = request.pageKey?.trim() || generatePageKey(request.title);

  return {
    name,
    pageKey,
    title: request.title,
    description: request.description,
    kind: request.kind,
    blocks: [],
    metaInfo: {
      templateId: request.templateId,
      componentCount: 0,
    },
    tags: request.tags ? { list: request.tags } : undefined,
    extension: {
      viewModelCode: request.viewModelCode,
    },
    semver: '0.1.0',
  };
}

/**
 * Create page update payload from DSL schema.
 * Maps DSL fields to backend PageSchemaUpdateRequest fields:
 *   - blocks (JSONB array)
 *   - layout (JSONB object)
 *   - kind
 *   - title (string)
 *   - metaInfo
 */
export function createDslSchemaPayload(
  schema: PageSchema,
  componentCount: number,
): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    metaInfo: {
      componentCount,
      lastModified: new Date().toISOString(),
    },
    blocks: schema.blocks,
    layout: schema.layout,
    kind: schema.kind,
    schemaVersion: schema.schemaVersion,
  };

  if (schema.extension && Object.keys(schema.extension).length > 0) {
    payload.extension = { ...schema.extension };
  }
  if (schema.title != null) {
    payload.title =
      typeof schema.title === 'string' ? schema.title : JSON.stringify(schema.title);
  }

  return payload;
}
