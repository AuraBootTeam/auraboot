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
  ApiPageType,
  ApiPageStatus,
} from './api-types';
import type { PageMeta, PageStatus, PageMode, CreatePageRequest } from './types';

/**
 * Map frontend PageMode to backend ApiPageType
 */
export function toApiPageType(mode: PageMode): ApiPageType {
  const mapping: Record<PageMode, ApiPageType> = {
    grid: 'dashboard',
    floor: 'detail',
    form: 'form',
  };
  return mapping[mode] || 'custom';
}

/**
 * Map backend ApiPageType to frontend PageMode
 */
export function fromApiPageType(pageType: ApiPageType): PageMode {
  const mapping: Record<ApiPageType, PageMode> = {
    form: 'form',
    list: 'form', // List pages use form mode
    detail: 'floor',
    dashboard: 'grid',
    custom: 'grid', // Default custom to grid
  };
  return mapping[pageType] || 'grid';
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
 * Convert PageSchemaDTO to PageMeta
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

  // Get component count from metaInfo or dslSchema
  let componentCount = 0;
  if (dto.metaInfo?.componentCount) {
    componentCount = dto.metaInfo.componentCount as number;
  } else if (dto.dslSchema?.components) {
    componentCount = (dto.dslSchema.components as unknown[]).length;
  }

  return {
    id: dto.pid,
    title: dto.title || dto.name,
    description: dto.description,
    mode: fromApiPageType(dto.pageType),
    viewModelCode: dto.extension?.viewModelCode as string | undefined,
    status: fromApiPageStatus(dto.status || 'draft', dto.isPublished),
    version: dto.semver || `${dto.version || 1}.0.0`,
    publishedVersion: dto.isPublished ? dto.semver : undefined,
    createdAt: dto.createdAt || new Date().toISOString(),
    updatedAt: dto.updatedAt || new Date().toISOString(),
    tags,
    thumbnail: dto.metaInfo?.thumbnail as string | undefined,
    componentCount,
    dslSchema: dto.dslSchema,
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
  if (page.mode !== undefined) {
    request.pageType = toApiPageType(page.mode);
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
    pageType: toApiPageType(request.mode),
    dslSchema: {
      version: '1.0.0',
      type: request.mode,
      components: [],
      layout: request.layoutPreset
        ? { columns: parseInt(request.layoutPreset.split('-')[1]) }
        : undefined,
    },
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
 * Extract DSL schema from PageSchemaDTO
 */
export function extractDslSchema(dto: PageSchemaDTO): Record<string, unknown> {
  return (
    dto.dslSchema || {
      version: '1.0.0',
      type: fromApiPageType(dto.pageType),
      components: [],
    }
  );
}

/**
 * Create DSL schema update payload
 */
export function createDslSchemaPayload(
  schema: Record<string, unknown>,
  componentCount: number,
): Record<string, unknown> {
  return {
    dslSchema: schema,
    metaInfo: {
      componentCount,
      lastModified: new Date().toISOString(),
    },
  };
}
