export interface ServerTablePage {
  current: number;
  pageSize: number;
  total: number;
}

export class ServerTablePageError extends Error {
  constructor() { super('Invalid server table page contract'); }
}

/** Opt-in server paging consumes explicit metadata rather than inferring a total from visible rows. */
export function readServerTablePage(value: unknown, pageField: 'pageNum' | 'current' = 'pageNum'): ServerTablePage {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ServerTablePageError();
  const page = value as Record<string, unknown>;
  const current = page[pageField], pageSize = page.pageSize, total = page.total;
  if (!Array.isArray(page.records) || page.records.some(row => !row || typeof row !== 'object' || Array.isArray(row))
    || typeof current !== 'number' || !Number.isSafeInteger(current) || current < 1
    || typeof pageSize !== 'number' || !Number.isSafeInteger(pageSize) || pageSize < 1
    || typeof total !== 'number' || !Number.isSafeInteger(total) || total < 0
    || page.records.length > pageSize || page.records.length > total
    || current > Math.max(1, Math.ceil(total / pageSize))
    || page.records.length !== Math.min(pageSize, total - (current - 1) * pageSize)) throw new ServerTablePageError();
  return { current, pageSize, total };
}
