/**
 * PageManagerService
 *
 * Service for managing pages in the page designer.
 * Uses backend API as primary storage, localStorage for draft cache.
 *
 * @since 3.2.0
 */

import type {
  PageMeta,
  PageListFilter,
  PageListSort,
  PageListResult,
  CreatePageRequest,
  UpdatePageRequest,
  PageTemplate,
  PageMode,
} from './types';
import * as pageApi from './pageApi';
import { toPageMeta, toCreateRequest, toUpdateRequest, createDslSchemaPayload } from './converters';
import { ResultHelper } from '~/utils/type';

/**
 * Default templates (local fallback)
 */
const DEFAULT_TEMPLATES: PageTemplate[] = [
  {
    id: 'tpl-customer-detail',
    name: '客户详情',
    description: '客户信息展示与编辑',
    mode: 'floor',
    category: '业务模板',
    isBuiltIn: true,
  },
  {
    id: 'tpl-order-form',
    name: '订单表单',
    description: '订单创建与编辑',
    mode: 'form',
    category: '业务模板',
    isBuiltIn: true,
  },
  {
    id: 'tpl-dashboard',
    name: '数据看板',
    description: '数据统计与可视化',
    mode: 'grid',
    category: '业务模板',
    isBuiltIn: true,
  },
  {
    id: 'tpl-blank-grid',
    name: '空白网格页',
    description: '从空白开始的网格布局',
    mode: 'grid',
    category: '空白模板',
    isBuiltIn: true,
  },
  {
    id: 'tpl-blank-floor',
    name: '空白楼层页',
    description: '从空白开始的楼层布局',
    mode: 'floor',
    category: '空白模板',
    isBuiltIn: true,
  },
  {
    id: 'tpl-blank-form',
    name: '空白表单页',
    description: '从空白开始的表单布局',
    mode: 'form',
    category: '空白模板',
    isBuiltIn: true,
  },
];

/**
 * Draft cache key prefix
 */
const DRAFT_CACHE_KEY = 'aura-page-designer-drafts';

/**
 * PageManagerService class
 */
export class PageManagerService {
  private static instance: PageManagerService;
  private draftCache: Map<string, { schema: Record<string, unknown>; timestamp: number }> =
    new Map();
  private templates: PageTemplate[] = [...DEFAULT_TEMPLATES];

  private constructor() {
    this.loadDraftCache();
  }

  /**
   * Get singleton instance
   */
  public static getInstance(): PageManagerService {
    if (!PageManagerService.instance) {
      PageManagerService.instance = new PageManagerService();
    }
    return PageManagerService.instance;
  }

  /**
   * Ensure data is loaded (call this on client-side mount)
   */
  public ensureLoaded(): void {
    if (this.isBrowser()) {
      this.loadDraftCache();
    }
  }

  /**
   * Check if running in browser
   */
  private isBrowser(): boolean {
    return typeof window !== 'undefined' && typeof localStorage !== 'undefined';
  }

  /**
   * Load draft cache from localStorage
   */
  private loadDraftCache(): void {
    if (!this.isBrowser()) return;

    try {
      const stored = localStorage.getItem(DRAFT_CACHE_KEY);
      if (stored) {
        const data = JSON.parse(stored) as Record<
          string,
          { schema: Record<string, unknown>; timestamp: number }
        >;
        Object.entries(data).forEach(([key, value]) => {
          this.draftCache.set(key, value);
        });
      }
    } catch (error) {
      console.error('Failed to load draft cache:', error);
    }
  }

  /**
   * Save draft cache to localStorage
   */
  private saveDraftCache(): void {
    if (!this.isBrowser()) return;

    try {
      const data: Record<string, { schema: Record<string, unknown>; timestamp: number }> = {};
      this.draftCache.forEach((value, key) => {
        data[key] = value;
      });
      localStorage.setItem(DRAFT_CACHE_KEY, JSON.stringify(data));
    } catch (error) {
      console.error('Failed to save draft cache:', error);
    }
  }

  /**
   * Cache draft schema locally
   */
  public cacheDraft(pageId: string, schema: Record<string, unknown>): void {
    this.draftCache.set(pageId, {
      schema,
      timestamp: Date.now(),
    });
    this.saveDraftCache();
  }

  /**
   * Get cached draft schema
   */
  public getCachedDraft(pageId: string): Record<string, unknown> | null {
    const cached = this.draftCache.get(pageId);
    return cached?.schema || null;
  }

  /**
   * Clear cached draft
   */
  public clearDraftCache(pageId: string): void {
    this.draftCache.delete(pageId);
    this.saveDraftCache();
  }

  /**
   * Get page list from API
   */
  public async getPageList(
    filter: PageListFilter = {},
    sort: PageListSort = { field: 'updatedAt', direction: 'desc' },
    page = 1,
    pageSize = 12,
  ): Promise<PageListResult> {
    try {
      const result = await pageApi.listPages({
        keyword: filter.query,
        isPublished:
          filter.status === 'published' ? true : filter.status === 'draft' ? false : undefined,
        page,
        pageSize,
        sortField:
          sort.field === 'title' ? 'title' : sort.field === 'createdAt' ? 'createdAt' : 'updatedAt',
        sortDirection: sort.direction === 'asc' ? 'asc' : 'desc',
      });

      if (ResultHelper.isSuccess(result) && result.data) {
        const rawItems = result.data.records ?? [];
        const items = rawItems.map(toPageMeta);

        // Apply local filters that backend doesn't support
        let filteredItems = items;
        if (filter.mode && filter.mode !== 'all') {
          filteredItems = items.filter((p) => p.mode === filter.mode);
        }
        if (filter.viewModelCode) {
          filteredItems = filteredItems.filter((p) => p.viewModelCode === filter.viewModelCode);
        }
        if (filter.tags && filter.tags.length > 0) {
          filteredItems = filteredItems.filter((p) =>
            filter.tags!.some((tag) => p.tags?.includes(tag)),
          );
        }

        return {
          items: filteredItems,
          pagination: {
            page: result.data.page,
            pageSize: result.data.pageSize ?? pageSize,
            total: result.data.total,
            totalPages: result.data.totalPages,
          },
        };
      }

      console.error('Failed to fetch page list:', result.desc);
      return { items: [], pagination: { page: 1, pageSize: 12, total: 0, totalPages: 0 } };
    } catch (error) {
      console.error('Failed to fetch page list:', error);
      return { items: [], pagination: { page: 1, pageSize: 12, total: 0, totalPages: 0 } };
    }
  }

  /**
   * Get page by ID from API
   */
  public async getPage(id: string): Promise<PageMeta | null> {
    try {
      const result = await pageApi.getPageByPid(id);

      if (ResultHelper.isSuccess(result) && result.data) {
        return toPageMeta(result.data);
      }

      console.error('Failed to fetch page:', result.desc);
      return null;
    } catch (error) {
      console.error('Failed to fetch page:', error);
      return null;
    }
  }

  /**
   * Create new page via API
   */
  public async createPage(request: CreatePageRequest): Promise<PageMeta> {
    const apiRequest = toCreateRequest(request);
    const result = await pageApi.createPage(apiRequest);

    if (ResultHelper.isSuccess(result) && result.data) {
      return toPageMeta(result.data);
    }

    // Parse error response for detailed field errors
    const resultAny = result as unknown as { context?: Record<string, string> };
    const context = resultAny.context;

    // Build detailed error message
    let errorMsg = result.desc || `创建失败 (code: ${result.code})`;

    // If there are field-specific errors in context, include them
    if (context && typeof context === 'object') {
      const fieldErrors = Object.entries(context)
        .map(([field, msg]) => `${field}: ${msg}`)
        .join('; ');
      if (fieldErrors) {
        errorMsg = `参数校验失败: ${fieldErrors}`;
      }
    }

    console.error('[PageManagerService] Create page failed:', {
      code: result.code,
      desc: result.desc,
      context,
    });
    throw new Error(errorMsg);
  }

  /**
   * Update page via API
   */
  public async updatePage(id: string, request: UpdatePageRequest): Promise<PageMeta | null> {
    const apiRequest = toUpdateRequest(request);
    const result = await pageApi.updatePage(id, apiRequest);

    if (ResultHelper.isSuccess(result) && result.data) {
      return toPageMeta(result.data);
    }

    console.error('Failed to update page:', result.desc);
    return null;
  }

  /**
   * Delete page via API
   */
  public async deletePage(id: string): Promise<boolean> {
    const result = await pageApi.deletePage(id);

    if (ResultHelper.isSuccess(result)) {
      // Clear local draft cache
      this.clearDraftCache(id);
      return true;
    }

    console.error('Failed to delete page:', result.desc);
    return false;
  }

  /**
   * Duplicate page
   */
  public async duplicatePage(id: string): Promise<PageMeta | null> {
    // Get original page
    const original = await this.getPage(id);
    if (!original) {
      return null;
    }

    // Create new page with duplicated content
    const newPage = await this.createPage({
      title: `${original.title} (副本)`,
      description: original.description,
      mode: original.mode,
      viewModelCode: original.viewModelCode,
      tags: original.tags,
    });

    return newPage;
  }

  /**
   * Archive page
   */
  public async archivePage(id: string): Promise<PageMeta | null> {
    // Use unpublish API as archive equivalent
    const result = await pageApi.unpublishPage(id);

    if (ResultHelper.isSuccess(result) && result.data) {
      const page = toPageMeta(result.data);
      page.status = 'archived';
      return page;
    }

    return null;
  }

  /**
   * Publish page via API
   */
  public async publishPage(id: string): Promise<PageMeta | null> {
    const result = await pageApi.publishPage(id);

    if (ResultHelper.isSuccess(result) && result.data) {
      // Clear draft cache on publish
      this.clearDraftCache(id);
      return toPageMeta(result.data);
    }

    console.error('Failed to publish page:', result.desc);
    return null;
  }

  /**
   * Get templates
   */
  public async getTemplates(mode?: PageMode): Promise<PageTemplate[]> {
    // Try to fetch from API first
    try {
      const result = await pageApi.getTemplates();
      if (ResultHelper.isSuccess(result) && result.data) {
        const apiTemplates: PageTemplate[] = result.data.map((dto) => ({
          id: dto.pid,
          name: dto.title || dto.name,
          description: dto.description,
          mode: dto.pageType === 'form' ? 'form' : dto.pageType === 'dashboard' ? 'grid' : 'floor',
          thumbnail: dto.metaInfo?.thumbnail as string | undefined,
          category: dto.templateCategory || '自定义模板',
          isBuiltIn: false,
        }));

        // Combine with default templates
        const allTemplates = [...DEFAULT_TEMPLATES, ...apiTemplates];

        if (mode) {
          return allTemplates.filter((t) => t.mode === mode);
        }
        return allTemplates;
      }
    } catch (error) {
      console.error('Failed to fetch templates from API:', error);
    }

    // Fallback to default templates
    if (mode) {
      return this.templates.filter((t) => t.mode === mode);
    }
    return [...this.templates];
  }

  /**
   * Get template by ID
   */
  public async getTemplate(id: string): Promise<PageTemplate | null> {
    const templates = await this.getTemplates();
    return templates.find((t) => t.id === id) || null;
  }

  /**
   * Update page schema (DSL)
   */
  public async updatePageSchema(
    id: string,
    schema: Record<string, unknown>,
    componentCount: number,
  ): Promise<void> {
    // Cache locally first for auto-save
    this.cacheDraft(id, schema);

    // Update via API
    const payload = createDslSchemaPayload(schema, componentCount);
    const result = await pageApi.updatePage(id, payload);

    if (!ResultHelper.isSuccess(result)) {
      console.error('Failed to update page schema:', result.desc);
      throw new Error(result.desc || 'Failed to save page schema');
    }

    // Clear draft cache on successful save
    this.clearDraftCache(id);
  }

  /**
   * Update page status (legacy method for compatibility)
   */
  public async updatePageStatus(id: string, componentCount: number): Promise<void> {
    const cached = this.getCachedDraft(id);
    if (cached) {
      await this.updatePageSchema(id, cached, componentCount);
    }
  }

  /**
   * Get version history
   */
  public async getVersionHistory(id: string): Promise<
    {
      id: number;
      version: string;
      operation: string;
      timestamp: string;
      operator?: string;
    }[]
  > {
    const result = await pageApi.getVersionHistory(id);

    if (ResultHelper.isSuccess(result) && result.data) {
      return result.data.map((v) => ({
        id: v.id,
        version: v.semver || `v${v.version}`,
        operation: v.operation,
        timestamp: v.operationTime || new Date().toISOString(),
        operator: v.operatorPid,
      }));
    }

    return [];
  }

  /**
   * Rollback to version
   */
  public async rollbackToVersion(id: string, historyId: number, reason: string): Promise<boolean> {
    const result = await pageApi.rollbackToVersion(id, historyId, reason);
    return ResultHelper.isSuccess(result);
  }
}

/**
 * Singleton instance
 */
export const pageManagerService = PageManagerService.getInstance();

export default PageManagerService;
