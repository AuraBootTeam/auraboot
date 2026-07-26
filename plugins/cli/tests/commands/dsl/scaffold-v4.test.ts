import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { scaffoldCommand } from '../../../src/commands/dsl/scaffold.js';
import { loadPlugin } from '../../../src/utils/plugin-loader.js';
import { validateStructural } from '../../../src/validation/structural.js';

describe('dsl scaffold emits an importable v4 plugin contract', () => {
  let target: string;

  beforeEach(() => {
    target = mkdtempSync(join(tmpdir(), 'aura-dsl-scaffold-v4-'));
    writeFileSync(join(target, 'plugin.json'), JSON.stringify({
      pluginId: 'com.acme.feedback',
      version: '1.0.0',
      namespace: 'feedback',
      displayName: 'Feedback',
      type: 'config',
    }, null, 2));
    vi.spyOn(console, 'log').mockImplementation(() => undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    rmSync(target, { recursive: true, force: true });
  });

  const read = (name: string) =>
    JSON.parse(readFileSync(join(target, 'config', `${name}.json`), 'utf8'));

  it('normalizes documented field shorthands and generates list/form/detail v4 pages', async () => {
    await scaffoldCommand('model', 'feedback_entry', {
      dir: target,
      pretty: false,
      quiet: true,
      namespace: 'feedback',
      fields: 'title:STRING,content:TEXTAREA,status:SELECT,owner:REFERENCE:sys_user',
    });

    expect(read('fields').map((field: any) => field.dataType)).toEqual([
      'string',
      'text',
      'enum',
      'reference',
    ]);
    expect(read('fields')[3].extension.referenceModel).toBe('sys_user');

    const pages = read('pages');
    expect(pages.map((page: any) => page.kind)).toEqual(['list', 'form', 'detail']);
    expect(pages.map((page: any) => page.pageKey)).toEqual([
      'feedback_entry_list',
      'feedback_entry_form',
      'feedback_entry_detail',
    ]);

    for (const page of pages) {
      expect(page.schemaVersion).toBe(4);
      expect(page.layout).toEqual({ type: 'stack' });
      expect(page.blocks.length).toBeGreaterThan(0);
      expect(page).not.toHaveProperty('pageType');
      expect(page).not.toHaveProperty('dslSchema');
      for (const block of page.blocks) {
        expect(block.id).toBeTruthy();
        expect(block.blockType).toBeTruthy();
      }
    }

    const [list, form, detail] = pages;
    expect(list.blocks.find((block: any) => block.blockType === 'table')).toBeTruthy();
    expect(list.blocks.find((block: any) => block.blockType === 'toolbar')
      ?.buttons[0].navigateTo).toBe('feedback_entry_form');
    expect(form.blocks.find((block: any) => block.blockType === 'form-section')).toBeTruthy();
    expect(detail.blocks.find((block: any) => block.blockType === 'form-section')?.readOnly).toBe(true);
    expect(form.blocks[0].fields[0].required).toBe(true);
    expect(read('menus')[0].pageKey).toBe('feedback_entry_list');
    expect(read('menus')[0].path).toBe('/p/feedback_entry');

    const structural = validateStructural(loadPlugin(target));
    expect(structural.messages.filter((message) =>
      message.severity === 'error' && message.path?.startsWith('config/pages.json'),
    )).toEqual([]);
  });

  it('upserts generated pages instead of duplicating existing page keys', async () => {
    await scaffoldCommand('model', 'feedback_entry', {
      dir: target,
      pretty: false,
      quiet: true,
      namespace: 'feedback',
      fields: 'title:STRING,status:SELECT',
    });
    await scaffoldCommand('pages', 'feedback_entry', {
      dir: target,
      pretty: false,
      quiet: true,
    });

    const pages = read('pages');
    expect(pages).toHaveLength(3);
    expect(new Set(pages.map((page: any) => page.pageKey)).size).toBe(3);
  });

  it('the local structural validator rejects legacy nested page shapes', () => {
    mkdirSync(join(target, 'config'), { recursive: true });
    writeFileSync(join(target, 'config/pages.json'), JSON.stringify([{
      pageKey: 'feedback_entry_list',
      pageType: 'list',
      modelCode: 'feedback_entry',
      dslSchema: {
        kind: 'List',
        version: '1.0.0',
        layout: {},
        areas: {},
      },
    }], null, 2));

    const result = validateStructural(loadPlugin(target));
    expect(result.messages.some((message) =>
      message.severity === 'error' && message.path?.startsWith('config/pages.json'),
    )).toBe(true);
  });

  it('the local structural validator rejects block types closed by the runtime registry', () => {
    mkdirSync(join(target, 'config'), { recursive: true });
    writeFileSync(join(target, 'config/pages.json'), JSON.stringify([{
      pageKey: 'feedback_entry_list',
      kind: 'list',
      schemaVersion: 4,
      modelCode: 'feedback_entry',
      layout: { type: 'stack' },
      blocks: [{ id: 'records', blockType: 'data-table' }],
    }], null, 2));

    const result = validateStructural(loadPlugin(target));
    expect(result.messages.some((message) =>
      message.severity === 'error'
      && message.path?.includes('/blocks/0/blockType'),
    )).toBe(true);
  });
});
