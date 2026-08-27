import {
  compareVersions,
  createPage,
  createVersion,
  getPageByPageKey,
  getPageByPid,
  getVersionHistory,
  publishPage,
  rollbackToVersion,
  unpublishPage,
  updatePage,
} from '../../studio/services/page-manager/pageApi';
import type {
  ApiPageStatus,
  ApiPageType,
  PageSchemaCreateRequest,
  PageSchemaDTO,
  PageSchemaUpdateRequest,
  PageSchemaVersionComparisonDTO,
  PageSchemaVersionCreateRequest,
  PageSchemaVersionDTO,
} from '../../studio/services/page-manager/api-types';
import type { Result } from '~/shared/services/http-client';
import type {
  LegacyDslBlockV2,
  LegacyPageSchemaV2,
  PageSchemaV3,
  PageSchemaV3Kind,
} from '../types';
import { migratePageSchemaV2ToV3 } from '../migration/migrateToV3';
import {
  FLAT_PAGE_SCHEMA_VERSION,
  FlatSerializationError,
  serializePageTreeToFlat,
} from './flatPageSerializer';
import {
  validatePageSchemaV3,
  type PageSchemaV3ValidationResult,
} from '../validation/validatePageSchemaV3';

export interface PageSchemaV3Api {
  getPageByPid: (pid: string) => Promise<ResultLike<PageSchemaDTO>>;
  getPageByPageKey: (pageKey: string) => Promise<ResultLike<PageSchemaDTO>>;
  updatePage: (pid: string, request: PageSchemaUpdateRequest) => Promise<ResultLike<PageSchemaDTO>>;
  createPage: (request: PageSchemaCreateRequest) => Promise<ResultLike<PageSchemaDTO>>;
  // Optional so load/save unit tests can supply a minimal api without the
  // lifecycle methods; the publish/unpublish helpers below assert their presence.
  publishPage?: (pid: string) => Promise<ResultLike<PageSchemaDTO>>;
  unpublishPage?: (pid: string) => Promise<ResultLike<PageSchemaDTO>>;
  // Version history / snapshot / rollback. Optional for the same reason — the
  // version helpers below assert their presence and surface a clear error when a
  // minimal api omits them.
  getVersionHistory?: (pid: string) => Promise<ResultLike<PageSchemaVersionDTO[]>>;
  createVersion?: (
    pid: string,
    request: PageSchemaVersionCreateRequest,
  ) => Promise<ResultLike<PageSchemaVersionDTO>>;
  rollbackToVersion?: (
    pid: string,
    historyId: number,
    reason: string,
  ) => Promise<ResultLike<PageSchemaVersionDTO>>;
  // Diff/compare two history versions. Optional for the same reason — the compare
  // helper below asserts its presence and surfaces a clear error when a minimal
  // api omits it.
  compareVersions?: (
    pid: string,
    fromHistoryId: number,
    toHistoryId: number,
  ) => Promise<ResultLike<PageSchemaVersionComparisonDTO>>;
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
  /** True when the loaded page is currently published (status === 'published'). */
  published: boolean;
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

export interface PublishPageSchemaV3Options {
  pid: string;
  api?: PageSchemaV3Api;
}

export interface PublishPageSchemaV3Result {
  ok: boolean;
  status?: ApiPageStatus;
  publishedAt?: string;
  error?: string;
}

const defaultApi: PageSchemaV3Api = {
  getPageByPid: getPageByPid as (pid: string) => Promise<ResultLike<PageSchemaDTO>>,
  getPageByPageKey: getPageByPageKey as (pageKey: string) => Promise<ResultLike<PageSchemaDTO>>,
  updatePage: updatePage as (
    pid: string,
    request: PageSchemaUpdateRequest,
  ) => Promise<Result<PageSchemaDTO>>,
  createPage: createPage as (request: PageSchemaCreateRequest) => Promise<Result<PageSchemaDTO>>,
  publishPage: publishPage as (pid: string) => Promise<Result<PageSchemaDTO>>,
  unpublishPage: unpublishPage as (pid: string) => Promise<Result<PageSchemaDTO>>,
  getVersionHistory: getVersionHistory as (
    pid: string,
  ) => Promise<Result<PageSchemaVersionDTO[]>>,
  createVersion: createVersion as (
    pid: string,
    request: PageSchemaVersionCreateRequest,
  ) => Promise<Result<PageSchemaVersionDTO>>,
  rollbackToVersion: rollbackToVersion as (
    pid: string,
    historyId: number,
    reason: string,
  ) => Promise<Result<PageSchemaVersionDTO>>,
  compareVersions: compareVersions as (
    pid: string,
    fromHistoryId: number,
    toHistoryId: number,
  ) => Promise<Result<PageSchemaVersionComparisonDTO>>,
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
    published: dto.status === 'published' || dto.isPublished === true,
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

  // Serialize before touching the API so unmappable content (composite/dashboard
  // kinds, designer-only blocks) fails fast with a precise reason instead of
  // writing a dialect the runtime cannot render.
  let flatRequestBlocks: LegacyDslBlockV2[];
  let flatKind: PageSchemaV3Kind;
  try {
    const flat = serializePageTreeToFlat(document);
    flatRequestBlocks = flat.blocks;
    flatKind = flat.kind;
  } catch (error) {
    if (error instanceof FlatSerializationError) {
      return { ok: false, error: error.message };
    }
    throw error;
  }

  if (source.type === 'page' && source.pid) {
    const request = toUpdateRequest(document, source, flatKind, flatRequestBlocks);
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

  const request = toCreateRequest(document, source, flatKind, flatRequestBlocks);
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

/**
 * Publish a saved page (POST /api/pages/{pid}/publish).
 *
 * The backend transitions the page to the `published` status and stamps
 * `publishedAt`; the returned DTO carries the live status so the toolbar can
 * reflect the published state without a separate read. A non-'0' code is
 * surfaced as an error string (e.g. a 403 from missing page.page.manage) rather
 * than throwing, matching the save path so the toolbar can render inline
 * feedback instead of crashing the workbench.
 */
export async function publishPageSchemaV3({
  pid,
  api = defaultApi,
}: PublishPageSchemaV3Options): Promise<PublishPageSchemaV3Result> {
  if (!api.publishPage) {
    return { ok: false, error: 'Publish API is not available.' };
  }
  const result = await api.publishPage(pid);
  if (result.code !== '0') {
    return { ok: false, error: result.message || result.desc || 'Failed to publish page.' };
  }
  return {
    ok: true,
    status: result.data?.status ?? 'published',
    publishedAt: result.data?.publishedAt,
  };
}

/**
 * Unpublish a published page (POST /api/pages/{pid}/unpublish), returning the
 * page to draft. Mirrors {@link publishPageSchemaV3}: non-'0' codes become an
 * error string for inline toolbar feedback.
 */
export async function unpublishPageSchemaV3({
  pid,
  api = defaultApi,
}: PublishPageSchemaV3Options): Promise<PublishPageSchemaV3Result> {
  if (!api.unpublishPage) {
    return { ok: false, error: 'Unpublish API is not available.' };
  }
  const result = await api.unpublishPage(pid);
  if (result.code !== '0') {
    return { ok: false, error: result.message || result.desc || 'Failed to unpublish page.' };
  }
  return {
    ok: true,
    status: result.data?.status ?? 'draft',
    publishedAt: result.data?.publishedAt,
  };
}

export interface PageVersionResult<T> {
  ok: boolean;
  data?: T;
  error?: string;
}

/**
 * List the version history of a saved page (GET /api/pages/{pid}/versions).
 *
 * Returns the raw PageSchemaVersionDTO[] newest-first as the backend orders
 * them. A non-'0' code (e.g. a 403 from missing page.page.read) is surfaced as
 * an error string rather than thrown, matching the publish helpers so the panel
 * can render inline feedback instead of crashing the workbench.
 */
export async function getPageVersions(
  pid: string,
  api: PageSchemaV3Api = defaultApi,
): Promise<PageVersionResult<PageSchemaVersionDTO[]>> {
  if (!api.getVersionHistory) {
    return { ok: false, error: 'Version history API is not available.' };
  }
  const result = await api.getVersionHistory(pid);
  if (result.code !== '0') {
    return { ok: false, error: result.message || result.desc || 'Failed to load versions.' };
  }
  return { ok: true, data: result.data ?? [] };
}

/**
 * Create a version snapshot of the saved page (POST /api/pages/{pid}/versions).
 *
 * The reason is sent as the version description; the backend defaults the
 * operation to "snapshot" here so the entry is distinguishable from publish /
 * rollback history rows.
 */
export async function createPageVersion(
  pid: string,
  reason: string,
  api: PageSchemaV3Api = defaultApi,
): Promise<PageVersionResult<PageSchemaVersionDTO>> {
  if (!api.createVersion) {
    return { ok: false, error: 'Create-version API is not available.' };
  }
  const result = await api.createVersion(pid, {
    operation: 'snapshot',
    description: reason,
  });
  if (result.code !== '0') {
    return { ok: false, error: result.message || result.desc || 'Failed to create version.' };
  }
  return { ok: true, data: result.data ?? undefined };
}

/**
 * Roll the saved page back to a history version
 * (POST /api/pages/{pid}/rollback/{historyId}?reason=...).
 *
 * The backend restores the target snapshot's blocks onto the live page and
 * bumps its version; callers should reload the page document afterwards to
 * reflect the restored canvas. A non-'0' code becomes an error string.
 */
export async function rollbackPageToVersion(
  pid: string,
  historyId: number,
  reason: string,
  api: PageSchemaV3Api = defaultApi,
): Promise<PageVersionResult<PageSchemaVersionDTO>> {
  if (!api.rollbackToVersion) {
    return { ok: false, error: 'Rollback API is not available.' };
  }
  const result = await api.rollbackToVersion(pid, historyId, reason);
  if (result.code !== '0') {
    return { ok: false, error: result.message || result.desc || 'Failed to roll back.' };
  }
  return { ok: true, data: result.data ?? undefined };
}

/**
 * Compare two history versions of a saved page
 * (GET /api/pages/{pid}/versions/{fromHistoryId}/compare/{toHistoryId}).
 *
 * Returns the backend's {@link PageSchemaVersionComparisonDTO} verbatim — the
 * panel renders stable-id block/property differences and semantic MOVE entries
 * exactly as the server computes them, without re-deriving policy-sensitive diff
 * semantics client-side. A non-'0' code (e.g. a 403 from
 * missing page.page.read, or a 404 when a history id is unknown) is surfaced as
 * an error string rather than thrown, matching the other version helpers so the
 * panel can render inline feedback instead of crashing the workbench.
 */
export async function comparePageVersions(
  pid: string,
  fromHistoryId: number,
  toHistoryId: number,
  api: PageSchemaV3Api = defaultApi,
): Promise<PageVersionResult<PageSchemaVersionComparisonDTO>> {
  if (!api.compareVersions) {
    return { ok: false, error: 'Compare-versions API is not available.' };
  }
  const result = await api.compareVersions(pid, fromHistoryId, toHistoryId);
  if (result.code !== '0') {
    return { ok: false, error: result.message || result.desc || 'Failed to compare versions.' };
  }
  return { ok: true, data: result.data ?? undefined };
}

/**
 * Stored-page → editor-tree materialization, dispatched explicitly on the
 * stored `schemaVersion`:
 *
 * - `3` — the page was stored as a recursive designer tree (legacy designer
 *   saves). Pass through unchanged; the next save re-serializes it to v4.
 * - `2` / `4` — the flat runtime dialect (`4` is
 *   `DslRegistry.PAGE_SCHEMA_CURRENT_VERSION`; stored `2` rows predate the
 *   2026-06 label bump and are structurally identical). Migrated into the
 *   editor tree by `migratePageSchemaV2ToV3`.
 * - anything else — fail fast. There is no guessing: a row labelled with an
 *   unknown version is either corrupt or newer than this client understands,
 *   and silently treating it as flat risks data loss on the next save.
 *
 * A flat-labelled row whose top-level blocks are recursive kind roots is
 * rejected loudly instead of being "migrated" — that combination means a
 * corrupt writer, and migrating it would duplicate the whole page under a
 * synthetic root.
 */
function toPageSchemaV3(dto: PageSchemaDTO): PageSchemaV3 {
  const version = dto.schemaVersion;
  if (version === 3) {
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
  if (version === 2 || version === 4) {
    assertFlatTopLevelBlocks(dto);
    return migratePageSchemaV2ToV3({
      schemaVersion: version,
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
  throw new Error(
    `Page "${dto.pageKey || dto.pid}" has unsupported schemaVersion ${String(
      version,
    )}; the designer understands stored versions 2, 3 and 4.`,
  );
}

const FLAT_KIND_ROOT_BLOCK_TYPES = new Set(['list', 'detail', 'form', 'dashboard']);

function assertFlatTopLevelBlocks(dto: PageSchemaDTO): void {
  const blocks = dto.blocks ?? [];
  const treeRoot = blocks.find(
    (block) =>
      block &&
      typeof block === 'object' &&
      FLAT_KIND_ROOT_BLOCK_TYPES.has(String(block.blockType)) &&
      Array.isArray(block.blocks),
  );
  if (treeRoot) {
    throw new Error(
      `Page "${dto.pageKey || dto.pid}" is labelled schemaVersion ${String(
        dto.schemaVersion,
      )} but stores recursive kind-root blocks (e.g. "${treeRoot.blockType}" with children); refusing to guess the dialect. Re-save the page from the designer or restore it from version history.`,
    );
  }
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

function toUpdateRequest(
  document: PageSchemaV3,
  source: PageSchemaV3Source,
  flatKind: PageSchemaV3Kind,
  flatBlocks: LegacyDslBlockV2[],
): PageSchemaUpdateRequest {
  return {
    name: document.pageKey || source.pageKey || document.id,
    pageKey: document.pageKey || source.pageKey || document.id,
    title: document.title,
    kind: toApiKind(flatKind),
    blocks: flatBlocks,
    layout: document.layout,
    schemaVersion: FLAT_PAGE_SCHEMA_VERSION,
    extension: document.extension,
  };
}

function toCreateRequest(
  document: PageSchemaV3,
  source: PageSchemaV3Source,
  flatKind: PageSchemaV3Kind,
  flatBlocks: LegacyDslBlockV2[],
): PageSchemaCreateRequest {
  const pageKey = document.pageKey || source.pageKey || document.id;
  return {
    name: pageKey,
    pageKey,
    title: resolveTitle(document.title, document.id),
    kind: toApiKind(flatKind),
    blocks: flatBlocks,
    schemaVersion: FLAT_PAGE_SCHEMA_VERSION,
    extension: document.extension,
  };
}

function unwrapResult<T>(result: ResultLike<T>, fallbackMessage: string): T {
  if (result.code !== '0' || !result.data) {
    throw new Error(result.message || result.desc || fallbackMessage);
  }
  return result.data;
}

function resolveTitle(title: PageSchemaV3['title'], fallback: string): string {
  if (!title) return fallback;
  if (typeof title === 'string') return title;
  return title.en || title['zh-CN'] || fallback;
}
