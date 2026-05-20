import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

interface PageManagerPage {
  pageKey: string;
  extension?: Record<string, unknown>;
  blocks?: Array<Record<string, any>>;
}

const PAGE_MANAGER_CONFIG_PATH = resolve(
  process.cwd(),
  '..',
  'plugins/page-manager/config/pages.json',
);

describe('page manager Unified Designer entry', () => {
  it('opens page schemas in Unified Designer by default and keeps a legacy editor action', () => {
    const pages = JSON.parse(readFileSync(PAGE_MANAGER_CONFIG_PATH, 'utf8')) as PageManagerPage[];
    const listPage = pages.find((page) => page.pageKey === 'page_schema_list');
    const formPage = pages.find((page) => page.pageKey === 'page_schema_form');
    const tableBlock = listPage?.blocks?.find((block) => block.id === 'ps_table');
    const rowActions = tableBlock?.rowActions ?? [];

    expect(tableBlock?.detailUrl).toBe('/unified-designer?pageId={pid}');
    expect(rowActions[0]).toMatchObject({
      code: 'edit_unified',
      action: { type: 'navigate', to: '/unified-designer?pageId={pid}' },
    });
    expect(rowActions).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          code: 'edit_legacy',
          action: { type: 'navigate', to: '/page-designer/{pid}' },
        }),
      ]),
    );
    expect(formPage?.extension?.afterSubmitRedirect).toBe('/unified-designer?pageId={pid}');
  });
});
