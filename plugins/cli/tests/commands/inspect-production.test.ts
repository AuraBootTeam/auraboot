import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { inspectCommand_ } from '../../src/commands/dsl/inspect.js';
import { listCommand } from '../../src/commands/dsl/list.js';

let dir: string;
let output: ReturnType<typeof vi.spyOn>;
function put(kind: string, records: unknown[]) {
  writeFileSync(join(dir, 'config', `${kind}.json`), JSON.stringify(records));
}
function text() { return output.mock.calls.map(call => call.join(' ')).join('\n'); }
async function inspect(type: string, code: string, pretty = false, quiet = false) {
  output.mockClear();
  await inspectCommand_(type, code, { dir, pretty, quiet });
  return pretty ? text() : JSON.parse(text());
}

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'aura-cli-inspect-'));
  mkdirSync(join(dir, 'config'));
  writeFileSync(join(dir, 'plugin.json'), JSON.stringify({ pluginId: 'demo', namespace: 'demo', version: '1.0.0' }));
  output = vi.spyOn(console, 'log').mockImplementation(() => {});
  vi.spyOn(process, 'exit').mockImplementation(code => { throw new Error(`CLI exit ${code}`); });
  put('models', [{ code: 'demo_order', displayName: 'Orders' }, { code: 'demo_empty', modelType: 'virtual' }]);
  put('fields', [{ code: 'amount', dataType: 'decimal' }, { code: 'status', dataType: 'dict', dictCode: 'state' },
    { code: 'legacy_status', dataType: 'dict', extension: { dictCode: 'state' } },
    { code: 'order_ref', dataType: 'reference', extension: { referenceModel: 'demo_order' } },
    { code: 'unbound_ref', dataType: 'reference', extension: { referenceModel: 'demo_order' } }]);
  put('bindings', [{ modelCode: 'demo_order', fieldCode: 'amount', required: true },
    { modelCode: 'demo_order', fieldCode: 'status' }, { modelCode: 'demo_empty', fieldCode: 'order_ref' }]);
  put('commands', [{ code: 'demo:update', type: 'update', modelCode: 'demo_order',
    inputFields: ['amount', 'missing'], permissions: ['demo.manage'] }, { code: 'demo:unbound' }]);
  put('pages', [{ pageKey: 'order_list', kind: 'list', modelCode: 'demo_order', layout: {},
    blocks: [{ blockType: 'table' }, { blockType: 'toolbar' }] },
    { pageKey: 'legacy', dslSchema: { kind: 'detail', modelCode: 'demo_order', areas: { empty: {},
      main: { blocks: [{ type: 'description' }, {}] } } } },
    { pageKey: 'missing_model', modelCode: 'missing', kind: 'form', blocks: [] },
    { pageKey: 'unbound', pageType: 'page', dslSchema: {} }]);
  put('permissions', [{ code: 'demo.manage' }, { code: 'demo.unused' }]);
  put('menus', [{ code: 'root', permissionCode: 'demo.manage', visible: true },
    { code: 'child', parentCode: 'root', permissionCode: 'missing' }, { code: 'unrestricted' }]);
  put('dicts', [{ code: 'state', items: [{ value: 'draft', label: 'Draft' },
    { value: 'done', 'label:zh-CN': '已完成' }, { value: 'unknown' }] }, { code: 'empty' }]);
  put('i18n', [{ key: 'model.demo_order._meta.label', 'en-US': 'Orders' },
    { key: 'model.demo_order.amount.label', 'en-US': 'Amount' }]);
});
afterEach(() => { rmSync(dir, { recursive: true, force: true }); vi.restoreAllMocks(); });

describe('inspect command reads production resource graphs', () => {
  it.each([
    { type: 'model', code: 'demo_order' }, { type: 'models', code: 'demo_order' },
    { type: 'command', code: 'demo:update' }, { type: 'commands', code: 'demo:update' },
    { type: 'page', code: 'order_list' }, { type: 'pages', code: 'order_list' },
    { type: 'field', code: 'amount' }, { type: 'fields', code: 'amount' },
    { type: 'menu', code: 'root' }, { type: 'menus', code: 'root' },
    { type: 'permission', code: 'demo.manage' }, { type: 'permissions', code: 'demo.manage' },
    { type: 'dict', code: 'state' }, { type: 'dicts', code: 'state' },
    { type: 'MODELS', code: 'demo_order' },
  ])('supports $type with the standard output envelope', async ({ type, code }) => {
    const result = await inspect(type, code);
    expect(result).toMatchObject({ ok: true, command: 'dsl.inspect', pluginId: 'demo', data: { code } });
    expect(Number.isNaN(Date.parse(result.timestamp))).toBe(false);
  });
  it('resolves fields, commands, pages, references and missing translations for models', async () => {
    const { data } = await inspect('model', 'demo_order');
    expect(data.fields).toEqual([{ code: 'amount', dataType: 'decimal', required: true },
      { code: 'status', dataType: 'dict', required: false }]);
    expect(data.commands).toEqual([{ code: 'demo:update', type: 'update' }]);
    expect(data.pages).toEqual([{ pageKey: 'order_list', pageType: 'list' }, { pageKey: 'legacy', pageType: 'detail' }]);
    expect(data.referencedBy).toEqual([{ fieldCode: 'order_ref', fromModels: ['demo_empty'] },
      { fieldCode: 'unbound_ref', fromModels: [] }]);
    expect(data.i18n).toEqual({ hasModelLabel: true, totalFieldKeys: 2, missingFieldLabels: ['model.demo_order.status.label'] });
  });
  it('reports canonical flat page block types and a valid model association', async () => {
    expect((await inspect('page', 'order_list')).data).toMatchObject({ modelExists: true, blockTypes: ['table', 'toolbar'] });
  });
  it('reads wrapped and legacy area blocks including unknown types', async () => {
    expect((await inspect('page', 'legacy')).data).toMatchObject({ modelExists: true, blockTypes: ['description', 'unknown'] });
  });
  it('distinguishes an unknown model from an unbound page', async () => {
    expect((await inspect('page', 'missing_model')).data.modelExists).toBe(false);
    expect((await inspect('page', 'unbound')).data).not.toHaveProperty('modelExists');
  });
  it('resolves command input definitions and keeps missing fields explicit', async () => {
    expect((await inspect('command', 'demo:update')).data).toMatchObject({ modelExists: true,
      resolvedInputFields: [{ code: 'amount', dataType: 'decimal', exists: true },
        { code: 'missing', dataType: 'unknown', exists: false }] });
    expect((await inspect('command', 'demo:unbound')).data).toMatchObject({ modelExists: false, resolvedInputFields: [] });
  });
  it('resolves field bindings and menu permissions independently', async () => {
    expect((await inspect('field', 'amount')).data.boundModels).toEqual(['demo_order']);
    expect((await inspect('field', 'unbound_ref')).data.boundModels).toEqual([]);
    expect((await inspect('menu', 'root')).data).toMatchObject({ permissionExists: true, children: ['child'] });
    expect((await inspect('menu', 'child')).data).toMatchObject({ permissionExists: false, children: [] });
    expect((await inspect('menu', 'unrestricted')).data).not.toHaveProperty('permissionExists');
  });
  it('reports direct permission and dictionary consumers', async () => {
    expect((await inspect('permission', 'demo.manage')).data).toMatchObject({ usedByCommands: ['demo:update'], usedByMenus: ['root'] });
    expect((await inspect('permission', 'demo.unused')).data).toMatchObject({ usedByCommands: [], usedByMenus: [] });
    expect((await inspect('dict', 'state')).data.usedByFields).toEqual(['status', 'legacy_status']);
    expect((await inspect('dict', 'empty')).data.usedByFields).toEqual([]);
  });
  it('supports imported manifest resource aliases for bindings and translations', async () => {
    rmSync(join(dir, 'config', 'bindings.json')); rmSync(join(dir, 'config', 'i18n.json'));
    put('modelFieldBindings', [{ modelCode: 'demo_order', fieldCode: 'amount', required: true }]);
    put('i18nResources', [{ key: 'model.demo_order._meta.label' }, { key: 'model.demo_order.amount.label' }]);
    expect((await inspect('model', 'demo_order')).data).toMatchObject({
      fields: [{ code: 'amount', dataType: 'decimal', required: true }],
      i18n: { hasModelLabel: true, totalFieldKeys: 1, missingFieldLabels: [] },
    });
  });
  it.each(['model', 'command', 'page', 'field', 'menu', 'permission', 'dict'])('rejects missing %s resources', async type => {
    await expect(inspect(type, 'missing')).rejects.toThrow('CLI exit 1');
    expect(JSON.parse(text())).toMatchObject({ ok: false, errors: [{ code: 'not_found', suggestion: expect.any(String) }] });
  });
  it('rejects invalid types and missing codes before loading any files', async () => {
    await expect(inspect('invalid', 'x')).rejects.toThrow('CLI exit 2');
    expect(JSON.parse(text()).errors[0].code).toBe('invalid_type');
    output.mockClear();
    await expect(inspectCommand_('model', undefined, { dir: '/missing', pretty: true, quiet: true })).rejects.toThrow('CLI exit 2');
    expect(text()).toContain('missing_code');
  });
  it.each([{ type: 'model', code: 'demo_order', label: 'Fields:' }, { type: 'model', code: 'demo_empty', label: 'Model:' },
    { type: 'command', code: 'demo:update', label: 'Input Fields:' }, { type: 'page', code: 'order_list', label: 'Block Types:' },
    { type: 'field', code: 'amount', label: 'Bound To Models:' }, { type: 'menu', code: 'root', label: 'Children:' },
    { type: 'permission', code: 'demo.manage', label: 'Used By Commands:' }, { type: 'dict', code: 'state', label: '已完成' },
    { type: 'dict', code: 'empty', label: 'dict: empty' }])('prints readable $type details', async ({ type, code, label }) => {
    const printed = await inspect(type, code, true);
    expect(printed).toContain(label);
    expect(printed).not.toContain('[object Object]');
  });
  it('honors quiet mode while retaining requested details', async () => {
    const printed = await inspect('model', 'demo_order', true, true);
    expect(printed).not.toContain('[dsl.inspect]');
    expect(printed).toContain('Model: demo_order');
  });
});

describe('list command uses the same production resource graph', () => {
  async function list(type: string, model?: string, pretty = false) {
    output.mockClear();
    await listCommand(type, { dir, model, pretty, quiet: false });
    return pretty ? text() : JSON.parse(text());
  }
  it.each([{ type: 'model', count: 2 }, { type: 'models', count: 2 },
    { type: 'field', count: 5 }, { type: 'fields', count: 5 },
    { type: 'command', count: 2 }, { type: 'commands', count: 2 },
    { type: 'page', count: 4 }, { type: 'pages', count: 4 },
    { type: 'menu', count: 3 }, { type: 'menus', count: 3 },
    { type: 'permission', count: 2 }, { type: 'permissions', count: 2 },
    { type: 'dict', count: 2 }, { type: 'dicts', count: 2 }, { type: 'MODELS', count: 2 }])(
    'lists $type with the correct count and envelope', async ({ type, count }) => {
      const result = await list(type);
      expect(result).toMatchObject({ ok: true, command: 'dsl.list', pluginId: 'demo', data: { count } });
      expect(result.data.items).toHaveLength(count);
    },
  );
  it('summarizes the current flat page kind and wrapped model association', async () => {
    const result = await list('pages', 'demo_order');
    expect(result.data.items).toEqual([{ code: 'order_list', pageType: 'list', modelCode: 'demo_order' },
      { code: 'legacy', pageType: 'detail', modelCode: 'demo_order' }]);
  });
  it('reports model relationship counts and explicit model types', async () => {
    const result = await list('models');
    expect(result.data.items).toEqual([{ code: 'demo_order', modelType: 'entity', fieldCount: 2, commandCount: 1, pageCount: 2 },
      { code: 'demo_empty', modelType: 'virtual', fieldCount: 1, commandCount: 0, pageCount: 0 }]);
  });
  it('filters fields and commands by model without inventing missing definitions', async () => {
    expect((await list('fields', 'demo_order')).data.items).toEqual([
      { code: 'amount', dataType: 'decimal', modelCode: 'demo_order', required: true },
      { code: 'status', dataType: 'dict', modelCode: 'demo_order', required: false },
    ]);
    expect((await list('commands', 'demo_order')).data.items).toEqual([
      { code: 'demo:update', type: 'update', modelCode: 'demo_order', inputFieldCount: 2 },
    ]);
    expect((await list('fields')).data.items.find((field: { code: string }) => field.code === 'amount').boundTo).toBe('demo_order');
  });
  it.each(['fields', 'commands', 'pages'])('reports an empty %s model filter', async type => {
    expect((await list(type, 'absent_model')).data).toMatchObject({ count: 0, items: [] });
  });
  it.each(['models', 'fields', 'commands', 'pages', 'permissions', 'menus', 'dicts'])('prints a readable %s table', async type => {
    const printed = await list(type, undefined, true);
    expect(printed).toContain(type === 'pages' ? 'PageKey' : 'Code');
    expect(printed).toContain(`${type} found.`);
    expect(printed).not.toContain('[object Object]');
  });
  it('prints model-filtered required field values with matching headers', async () => {
    const printed = await list('fields', 'demo_order', true);
    expect(printed).toContain('Required'); expect(printed).toContain('true');
    expect(printed).toContain('false'); expect(printed).toContain('demo_order');
  });
  it('rejects an invalid resource type before loading files', async () => {
    await expect(list('invalid')).rejects.toThrow('CLI exit 2');
    expect(JSON.parse(text())).toMatchObject({ ok: false, errors: [{ code: 'invalid_type' }] });
  });
});
