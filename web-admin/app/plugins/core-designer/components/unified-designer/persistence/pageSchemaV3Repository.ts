import { createPage, getPageByPageKey, getPageByPid, updatePage } from '../../studio/services/page-manager/pageApi';
import type {
  ApiPageType,
  PageSchemaCreateRequest,
  PageSchemaDTO,
  PageSchemaUpdateRequest,
} from '../../studio/services/page-manager/api-types';
import type { Result } from '~/shared/services/http-client';
import type { LegacyPageSchemaV2, PageSchemaV3, PageSchemaV3Kind } from '../types';
import { migratePageSchemaV2ToV3 } from '../migration/migrateToV3';
import {
  validatePageSchemaV3,
  type PageSchemaV3ValidationResult,
} from '../validation/validatePageSchemaV3';

export interface PageSchemaV3Api {
  getPageByPid: (pid: string) => Promise<ResultLike<PageSchemaDTO>>;
  getPageByPageKey: (pageKey: string) => Promise<ResultLike<PageSchemaDTO>>;
  updatePage: (pid: string, request: PageSchemaUpdateRequest) => Promise<ResultLike<PageSchemaDTO>>;
  createPage: (request: PageSchemaCreateRequest) => Promise<ResultLike<PageSchemaDTO>>;
}

export interface ResultLike<T> {
  code: string;
  message?: string;
  desc?: string;
  data?: T | null;
}

export interface PageSchemaV3Source {
  type: 'page' | 'local';
  pid?: string;
  pageKey?: string;
}

export interface LoadedPageSchemaV3 {
  document: PageSchemaV3;
  source: PageSchemaV3Source;
}

export interface LoadPageSchemaV3Options {
  pageId?: string | null;
  pageKey?: string | null;
  api?: PageSchemaV3Api;
}

export interface SavePageSchemaV3Options {
  document: PageSchemaV3;
  source: PageSchemaV3Source;
  api?: PageSchemaV3Api;
}

export interface SavePageSchemaV3Result {
  ok: boolean;
  document?: PageSchemaV3;
  source?: PageSchemaV3Source;
  error?: string;
  validation?: PageSchemaV3ValidationResult;
}

const defaultApi: PageSchemaV3Api = {
  getPageByPid: getPageByPid as (pid: string) => Promise<ResultLike<PageSchemaDTO>>,
  getPageByPageKey: getPageByPageKey as (pageKey: string) => Promise<ResultLike<PageSchemaDTO>>,
  updatePage: updatePage as (
    pid: string,
    request: PageSchemaUpdateRequest,
  ) => Promise<Result<PageSchemaDTO>>,
  createPage: createPage as (request: PageSchemaCreateRequest) => Promise<Result<PageSchemaDTO>>,
};

export async function loadPageSchemaV3({
  pageId,
  pageKey,
  api = defaultApi,
}: LoadPageSchemaV3Options): Promise<LoadedPageSchemaV3> {
  if (!pageId && !pageKey) {
    throw new Error('pageId or pageKey is required to load a PageSchema V3 document.');
  }

  const result = pageId
    ? await api.getPageByPid(pageId)
    : await api.getPageByPageKey(pageKey as string);
  const dto = unwrapResult(result, 'Failed to load page schema.');
  const document = toPageSchemaV3(dto);

  return {
    document,
    source: {
      type: 'page',
      pid: dto.pid,
      pageKey: dto.pageKey,
    },
  };
}

export async function savePageSchemaV3({
  document,
  source,
  api = defaultApi,
}: SavePageSchemaV3Options): Promise<SavePageSchemaV3Result> {
  const validation = validatePageSchemaV3(document);
  if (!validation.valid) {
    return { ok: false, validation };
  }

  if (source.type === 'page' && source.pid) {
    const request = toUpdateRequest(document, source);
    const result = await api.updatePage(source.pid, request);
    if (result.code !== '0') {
      return { ok: false, error: result.message || result.desc || 'Failed to save page schema.' };
    }
    return {
      ok: true,
      document,
      source: {
        type: 'page',
        pid: source.pid,
        pageKey: request.pageKey,
      },
    };
  }

  const request = toCreateRequest(document, source);
  const result = await api.createPage(request);
  if (result.code !== '0') {
    return { ok: false, error: result.message || result.desc || 'Failed to create page schema.' };
  }

  return {
    ok: true,
    document,
    source: {
      type: 'page',
      pid: result.data?.pid,
      pageKey: result.data?.pageKey ?? request.pageKey,
    },
  };
}

function toPageSchemaV3(dto: PageSchemaDTO): PageSchemaV3 {
  if (dto.schemaVersion === 3 || hasRecursiveV3Blocks(dto.blocks)) {
    return {
      schemaVersion: 3,
      kind: normalizeKind(dto.kind),
      id: dto.pageKey || dto.name || dto.pid,
      pageKey: dto.pageKey,
      modelCode: dto.modelCode,
      title: dto.title,
      layout: dto.layout,
      blocks: dto.blocks ?? [],
      extension: dto.extension,
    };
  }

function hasRecursiveV3Blocks(blocks: PageSchemaDTO['blocks']): blocks is PageSchemaV3['blocks'] {
  if (!Array.isArray(blocks) || blocks.length === 0) return false;
  return blocks.some((block) => {
    if (!block || typeof block !== 'object') return false;
    const candidate = block as Record<string, unknown>;
    return (
      typeof candidate.id === 'string' &&
      typeof candidate.blockType === 'string' &&
      Array.isArray(candidate.blocks) &&
      ['list', 'detail', 'form', 'dashboard'].includes(candidate.blockType)
    );
  });
}

  return migratePageSchemaV2ToV3({
    schemaVersion: dto.schemaVersion,
    kind: dto.kind,
    id: dto.pageKey || dto.name || dto.pid,
    pageKey: dto.pageKey,
    modelCode: dto.modelCode,
    title: dto.title,
    layout: dto.layout,
    blocks: dto.blocks as LegacyPageSchemaV2['blocks'],
    extension: dto.extension,
  });
}

function toUpdateRequest(
  document: PageSchemaV3,
  source: PageSchemaV3Source,
): PageSchemaUpdateRequest {
  return {
    name: document.pageKey || source.pageKey || document.id,
    pageKey: document.pageKey || source.pageKey || document.id,
    title: document.title,
    kind: toApiKind(document.kind),
    blocks: document.blocks,
    layout: document.layout,
    schemaVersion: 3,
    extension: document.extension,
  };
}

function toCreateRequest(
  document: PageSchemaV3,
  source: PageSchemaV3Source,
): PageSchemaCreateRequest {
  const pageKey = document.pageKey || source.pageKey || document.id;
  return {
    name: pageKey,
    pageKey,
    title: resolveTitle(document.title, document.id),
    kind: toApiKind(document.kind),
    blocks: document.blocks,
    schemaVersion: 3,
    extension: document.extension,
  };
}

function unwrapResult<T>(result: ResultLike<T>, fallbackMessage: string): T {
  if (result.code !== '0' || !result.data) {
    throw new Error(result.message || result.desc || fallbackMessage);
  }
  return result.data;
}

function normalizeKind(kind: string): PageSchemaV3Kind {
  if (
    kind === 'list' ||
    kind === 'detail' ||
    kind === 'form' ||
    kind === 'dashboard' ||
    kind === 'composite'
  ) {
    return kind;
  }
  return 'composite';
}

function toApiKind(kind: PageSchemaV3Kind): ApiPageType {
  return kind;
}

function resolveTitle(title: PageSchemaV3['title'], fallback: string): string {
  if (!title) return fallback;
  if (typeof title === 'string') return title;
  return title.en || title['zh-CN'] || fallback;
}
