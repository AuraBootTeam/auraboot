import type { RelatedPage } from '~/shared/services/modelService';

/** Publication placeholders are not authored/generated runnable CRUD pages. */
export function isModelPageStub(page: RelatedPage): boolean {
  const extension = page.extension;
  if (!extension || typeof extension !== 'object' || Array.isArray(extension)) return false;
  const flag = (extension as Record<string, unknown>).auto_created;
  return flag === true || flag === 'true';
}

/** CRUD routes take a model code; custom routes take an exact stored page key. */
export function resolveModelPageRoute(page: RelatedPage): string {
  if (typeof page.route === 'string' && page.route.startsWith('/')) return page.route;
  const pageKey = page.pageKey || page.code || page.name;
  if (!pageKey) return '/p/page_schema';
  const modelCode = typeof page.modelCode === 'string' ? page.modelCode : undefined;
  if (modelCode && page.kind === 'list' && pageKey === `${modelCode}_list`) {
    return `/p/${encodeURIComponent(modelCode)}`;
  }
  if (modelCode && page.kind === 'form' && pageKey === `${modelCode}_form`) {
    return `/p/${encodeURIComponent(modelCode)}/new`;
  }
  return `/p/c/${encodeURIComponent(pageKey)}`;
}

/** A record-free primary preview should open the list before a form or detail. */
export function primaryModelPreview(pages: Partial<Record<'list' | 'form' | 'detail', RelatedPage>>) {
  return pages.list ?? pages.form ?? pages.detail;
}
